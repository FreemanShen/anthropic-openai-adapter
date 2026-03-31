package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Iterator;

/**
 * Anthropic Messages API 与 OpenAI Chat Completions API 之间的双向格式转换器。
 *
 * <h2>协议差异背景</h2>
 * Anthropic 和 OpenAI 的 API 格式存在多处差异，本 mapper 负责弥合这些差异：
 *
 * <h3>1. 请求格式差异</h3>
 * <table>
 *   <tr><th>字段</th><th>Anthropic</th><th>OpenAI</th></tr>
 *   <tr><td>模型指定</td><td>顶级 {@code model} 字段</td><td>顶级 {@code model} 字段</td></tr>
 *   <tr><td>系统提示</td><td>单独的 {@code system} 字段（数组或字符串）</td><td>role=system 的 message</td></tr>
 *   <tr><td>消息角色</td><td>user / assistant</td><td>user / assistant / system / tool</td></tr>
 *   <tr><td>图片内容</td><td>type=image, source={type, media_type, data/url}</td><td>type=image_url, image_url={url}</td></tr>
 *   <tr><td>工具调用</td><td>content[type=tool_use]</td><td>tool_calls / tool_call_id</td></tr>
 *   <tr><td>停止条件</td><td>stop_sequences（数组）</td><td>stop（数组或字符串）</td></tr>
 * </table>
 *
 * <h3>2. 响应格式差异</h3>
 * <table>
 *   <tr><th>字段</th><th>Anthropic</th><th>OpenAI</th></tr>
 *   <tr><td>消息类型</td><td>顶级 type=message</td><td>choices[].message</td></tr>
 *   <tr><td>停止原因</td><td>stop_reason（end_turn/tool_use/max_tokens）</td><td>finish_reason（stop/tool_calls/length）</td></tr>
 *   <tr><td>输入Token</td><td>input_tokens / cache_read_input_tokens</td><td>prompt_tokens / prompt_tokens_details.cached_tokens</td></tr>
 *   <tr><td>输出Token</td><td>output_tokens</td><td>completion_tokens</td></tr>
 *   <tr><td>思考过程</td><td><think>...</think> 标签（需过滤）</td><td>普通文本</td></tr>
 * </table>
 *
 * <h3>3. 流式 SSE 事件差异</h3>
 * OpenAI 流式每个 chunk 是：
 * <pre>data: {"choices":[{"delta":{"content":"Hello"}}]}</pre>
 * Anthropic 流式事件包括：message_start、content_block_start、content_block_delta、message_delta、message_stop。
 * 流式翻译不在此类中，详见 {@link AnthropicStreamTranslator}。
 *
 * @see AnthropicStreamTranslator
 */
@Component
public class AnthropicOpenAiMapper {

    private static final Logger log = LoggerFactory.getLogger(AnthropicOpenAiMapper.class);

    private final ObjectMapper objectMapper;
    private final ProxyProperties proxyProperties;

    public AnthropicOpenAiMapper(ObjectMapper objectMapper, ProxyProperties proxyProperties) {
        this.objectMapper = objectMapper;
        this.proxyProperties = proxyProperties;
    }

    /**
     * 将 Anthropic Messages 请求转换为 OpenAI Chat Completions 请求。
     *
     * 转换步骤：
     * <ol>
     *   <li>复制基础字段：model、max_tokens、temperature、top_p、metadata</li>
     *   <li>将 Anthropic 单独的 {@code system} 字段提取为 role=system 的 message</li>
     *   <li>遍历 messages 数组，根据 role 和 content 类型分别转换：
     *     <ul>
     *       <li>user/system 消息：处理 text、image 块</li>
     *       <li>assistant 消息：处理 text、tool_use 块</li>
     *       <li>tool 消息（tool_result）：转为 role=tool 的消息</li>
     *     </ul>
     *   </li>
     *   <li>转换 tools 定义（Anthropic input_schema → OpenAI parameters）</li>
     *   <li>转换 tool_choice（disable_parallel_tool_use → parallel_tool_calls）</li>
     *   <li>设置 stream_options.include_usage=true（用于流式响应携带 usage）</li>
     * </ol>
     *
     * @param anthropicRequest Anthropic 格式的 JSON 节点
     * @return OpenAI Chat Completions 格式的 JSON 节点
     */
    public JsonNode toOpenAiRequest(JsonNode anthropicRequest) {
        ObjectNode openAiRequest = objectMapper.createObjectNode();

        // --- 基础字段复制 ---
        openAiRequest.put("model", resolveModel(anthropicRequest));
        boolean stream = anthropicRequest.path("stream").asBoolean(false);
        openAiRequest.put("stream", stream);

        // 复制常见可选参数
        copyIfPresent(anthropicRequest, openAiRequest, "max_tokens", "max_tokens");
        copyIfPresent(anthropicRequest, openAiRequest, "temperature", "temperature");
        copyIfPresent(anthropicRequest, openAiRequest, "top_p", "top_p");
        copyIfPresent(anthropicRequest, openAiRequest, "metadata", "metadata");

        // 流式响应时要求上游携带 usage 信息
        if (stream) {
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            openAiRequest.set("stream_options", streamOptions);
        }

        // Anthropic 的 stop_sequences（数组）→ OpenAI 的 stop（数组）
        if (anthropicRequest.has("stop_sequences") && anthropicRequest.get("stop_sequences").isArray()) {
            openAiRequest.set("stop", anthropicRequest.get("stop_sequences"));
        }

        // Anthropic metadata.user_id → OpenAI user
        if (anthropicRequest.has("metadata") && anthropicRequest.path("metadata").hasNonNull("user_id")) {
            openAiRequest.put("user", anthropicRequest.path("metadata").path("user_id").asText());
        }

        // --- 构建 messages 数组 ---
        ArrayNode messages = objectMapper.createArrayNode();

        // Anthropic 独立的 system 字段 → role=system 的 message
        appendSystemMessages(anthropicRequest.get("system"), messages);

        // 遍历对话历史消息，转换为 OpenAI 格式
        appendConversationMessages(anthropicRequest.path("messages"), messages);
        openAiRequest.set("messages", messages);

        // --- 工具相关转换 ---
        // Anthropic tools → OpenAI tools
        if (anthropicRequest.has("tools")) {
            openAiRequest.set("tools", convertTools(anthropicRequest.get("tools")));
        }

        // Anthropic tool_choice（含 disable_parallel_tool_use）→ OpenAI tool_choice / parallel_tool_calls
        if (anthropicRequest.has("tool_choice")) {
            JsonNode toolChoice = anthropicRequest.get("tool_choice");
            openAiRequest.set("tool_choice", convertToolChoice(toolChoice));
            if (toolChoice.path("disable_parallel_tool_use").asBoolean(false)) {
                openAiRequest.put("parallel_tool_calls", false);
            }
        }

        return openAiRequest;
    }

    /**
     * 将 OpenAI Chat Completions 响应（非流式）转换为 Anthropic 响应格式。
     *
     * 转换步骤：
     * <ol>
     *   <li>构建响应框架：id、type=message、role=assistant、model</li>
     *   <li>提取 assistant 消息的 content，分块转为 Anthropic text 类型</li>
     *   <li>提取 tool_calls，转为 Anthropic tool_use 类型</li>
     *   <li>映射 finish_reason → stop_reason</li>
     *   <li>转换 usage：prompt_tokens / cached_tokens / completion_tokens</li>
     *   <li>过滤掉 <think>...</think> 思考过程文本</li>
     * </ol>
     *
     * @param openAiResponseBody 上游返回的 OpenAI JSON 字符串
     * @return Anthropic 格式的 JSON 节点
     */
    public JsonNode toAnthropicResponse(String openAiResponseBody) throws JsonProcessingException {
        JsonNode openAiResponse = objectMapper.readTree(openAiResponseBody);
        ObjectNode anthropicResponse = baseAnthropicMessage(openAiResponse);

        JsonNode choice = firstChoice(openAiResponse);
        JsonNode message = choice.path("message");

        // 构建 content 数组：文本块 + 工具调用块
        ArrayNode content = objectMapper.createArrayNode();
        appendAssistantContentBlocks(message.path("content"), content);
        appendToolUseBlocks(message.path("tool_calls"), content);

        anthropicResponse.set("content", content);
        anthropicResponse.put("stop_reason", mapFinishReason(choice.path("finish_reason").asText(null)));
        anthropicResponse.putNull("stop_sequence");
        anthropicResponse.set("usage", convertUsage(openAiResponse.path("usage")));

        return anthropicResponse;
    }

    /**
     * 将 OpenAI 错误响应转换为 Anthropic 错误格式。
     *
     * 状态码映射：
     * 400 → invalid_request_error
     * 401/403 → authentication_error
     * 404 → not_found_error
     * 429 → rate_limit_error
     * 其他 → api_error
     *
     * @param openAiErrorBody 上游返回的错误 JSON
     * @param statusCode     HTTP 状态码
     * @return Anthropic 错误格式的 JSON
     */
    public JsonNode toAnthropicError(String openAiErrorBody, int statusCode) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", "error");

        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("type", mapErrorType(statusCode));
        detail.put("message", extractErrorMessage(openAiErrorBody));
        error.set("error", detail);
        return error;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建 Anthropic 响应基础结构（id、type、role、model）。
     */
    private ObjectNode baseAnthropicMessage(JsonNode openAiResponse) {
        ObjectNode anthropicResponse = objectMapper.createObjectNode();
        anthropicResponse.put("id", openAiResponse.path("id").asText("msg_adapter"));
        anthropicResponse.put("type", "message");
        anthropicResponse.put("role", "assistant");
        anthropicResponse.put("model", openAiResponse.path("model").asText(proxyProperties.getDefaultModel()));
        return anthropicResponse;
    }

    /**
     * 安全获取第一个 choice（OpenAI 响应中 choices 是数组）。
     */
    private JsonNode firstChoice(JsonNode openAiResponse) {
        JsonNode choices = openAiResponse.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0);
        }
        return objectMapper.createObjectNode();
    }

    /**
     * 解析 model 字段，优先使用请求中的 model，无则使用配置的默认值。
     */
    private String resolveModel(JsonNode anthropicRequest) {
        if (anthropicRequest.hasNonNull("model")) {
            return anthropicRequest.get("model").asText();
        }
        return proxyProperties.getDefaultModel();
    }

    /**
     * 工具方法：若源字段存在则复制到目标节点。
     */
    private void copyIfPresent(JsonNode from, ObjectNode to, String sourceField, String targetField) {
        if (from.has(sourceField)) {
            to.set(targetField, from.get(sourceField));
        }
    }

    /**
     * 将 Anthropic 独立的 system 字段（或 system 数组）转换为 role=system 的 OpenAI message。
     *
     * Anthropic system 支持两种格式：
     * - 字符串："You are a helpful assistant."
     * - 数组：[{type:"text", text:"..."}]
     * 两者都会被提取合并为一个 role=system 的 message。
     */
    private void appendSystemMessages(JsonNode systemNode, ArrayNode messages) {
        String systemText = extractTextContent(systemNode);
        if (!StringUtils.hasText(systemText)) {
            return;
        }

        ObjectNode systemMessage = objectMapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemText);
        messages.add(systemMessage);
    }

    /**
     * 遍历 Anthropic messages 数组，根据 role 分发到对应的处理方法。
     *
     * - user / system：转为 OpenAI 同名 role（可能包含图片）
     * - assistant：处理 text + tool_use 块，工具调用转为 tool_calls 数组
     * - tool（tool_result）：提取 content 中的结果，转为 role=tool 的消息
     */
    private void appendConversationMessages(JsonNode anthropicMessages, ArrayNode messages) {
        if (anthropicMessages == null || !anthropicMessages.isArray()) {
            return;
        }

        for (JsonNode messageNode : anthropicMessages) {
            String role = messageNode.path("role").asText("user");
            JsonNode contentNode = messageNode.get("content");

            if (contentNode == null || contentNode.isNull()) {
                continue;
            }

            // content 为纯字符串时（常见于 user 消息），直接构造简单消息
            if (contentNode.isTextual()) {
                messages.add(simpleMessage(role, contentNode.asText()));
                continue;
            }
            if (!contentNode.isArray()) {
                log.warn("检测到不支持的 content 结构, role={}", role);
                continue;
            }

            // content 为数组时，根据 role 分发
            if ("assistant".equals(role)) {
                appendAssistantMessage(contentNode, messages);
            } else {
                // user / system / tool 走同一逻辑（处理 text / image / tool_result）
                appendNonAssistantMessage(role, contentNode, messages);
            }
        }
    }

    /**
     * 处理 assistant 消息的 content 数组。
     *
     * 将 Anthropic 的 text 块和 tool_use 块分别提取：
     * - text 块 → OpenAI message.content 中的 text part
     * - tool_use 块 → OpenAI message.tool_calls 数组
     *
     * 注意：当消息同时包含 tool_use 和 text 时，OpenAI 格式要求 tool_calls 存在，
     * 而 content 要么为 null 要么是 parts 数组。这里采用同时输出的方式。
     */
    private void appendAssistantMessage(JsonNode contentNode, ArrayNode messages) {
        ArrayNode messageContent = objectMapper.createArrayNode();
        ArrayNode toolCalls = objectMapper.createArrayNode();

        for (JsonNode block : contentNode) {
            String blockType = block.path("type").asText();
            if ("text".equals(blockType)) {
                messageContent.add(buildOpenAiTextPart(block.path("text").asText("")));
            } else if ("tool_use".equals(blockType)) {
                toolCalls.add(convertToolUseBlock(block));
            } else {
                log.warn("暂未支持的 assistant content block 类型: {}", blockType);
            }
        }

        ObjectNode assistantMessage = objectMapper.createObjectNode();
        assistantMessage.put("role", "assistant");

        // 若只有 tool_calls 没有 text，content 设为 null
        if (toolCalls.size() > 0 && messageContent.size() == 0) {
            assistantMessage.putNull("content");
        } else {
            setOpenAiContent(assistantMessage, messageContent);
        }

        if (toolCalls.size() > 0) {
            assistantMessage.set("tool_calls", toolCalls);
        }
        messages.add(assistantMessage);
    }

    /**
     * 处理非 assistant 角色（user/system/tool）的 content 数组。
     *
     * 支持的 block 类型：
     * - text：转为 OpenAI text part
     * - image（仅 user）：将 base64 或 URL 图片转为 OpenAI image_url part
     * - tool_result（仅 tool 角色的历史消息）：转为 role=tool 的独立消息
     */
    private void appendNonAssistantMessage(String role, JsonNode contentNode, ArrayNode messages) {
        ArrayNode messageContent = objectMapper.createArrayNode();

        for (JsonNode block : contentNode) {
            String blockType = block.path("type").asText();
            if ("text".equals(blockType)) {
                messageContent.add(buildOpenAiTextPart(block.path("text").asText("")));
            } else if ("image".equals(blockType)) {
                JsonNode imagePart = buildOpenAiImagePart(block);
                if (imagePart != null) {
                    messageContent.add(imagePart);
                }
            } else if ("tool_result".equals(blockType)) {
                // tool_result 不属于当前消息，而是作为独立的 role=tool 消息追加
                messages.add(convertToolResultBlock(block));
            } else {
                log.warn("暂未支持的 {} content block 类型: {}", role, blockType);
            }
        }

        // 仅当有实际文本内容时才创建消息（避免空消息）
        if (messageContent.size() > 0) {
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", role);
            setOpenAiContent(userMessage, messageContent);
            messages.add(userMessage);
        }
    }

    /**
     * 设置 OpenAI message 的 content 字段。
     *
     * OpenAI content 格式规则：
     * - 空数组 → content = ""
     * - 单个 text part → content 可以直接是字符串（简写形式）
     * - 多个 part 或包含 image_url → content = [parts...]
     */
    private void setOpenAiContent(ObjectNode message, ArrayNode contentParts) {
        if (contentParts.size() == 0) {
            message.put("content", "");
            return;
        }
        if (contentParts.size() == 1 && "text".equals(contentParts.get(0).path("type").asText())) {
            message.put("content", contentParts.get(0).path("text").asText());
            return;
        }
        message.set("content", contentParts);
    }

    /**
     * 构造一个简单的 role+content 消息（用于纯文本消息）。
     */
    private ObjectNode simpleMessage(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /**
     * 构建 OpenAI text part：{@code {"type":"text","text":"..."}}。
     */
    private JsonNode buildOpenAiTextPart(String text) {
        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", text);
        return textPart;
    }

    /**
     * 将 Anthropic 图片块转换为 OpenAI image_url part。
     *
     * Anthropic 图片有两种来源：
     * - base64：{@code source={type:"base64", media_type:"image/png", data:"..."}}
     * - URL：{@code source={type:"url", url:"https://..."}}
     *
     * 转换为 OpenAI 格式：{@code {type:"image_url", image_url:{url:"data:...;base64,..."}}}
     */
    private JsonNode buildOpenAiImagePart(JsonNode block) {
        JsonNode source = block.path("source");
        String sourceType = source.path("type").asText();
        String mediaType = source.path("media_type").asText("image/png");
        String url = null;

        if ("base64".equals(sourceType) && source.hasNonNull("data")) {
            // Anthropic base64 图片 → data URI 格式
            url = "data:" + mediaType + ";base64," + source.path("data").asText();
        } else if ("url".equals(sourceType) && source.hasNonNull("url")) {
            url = source.path("url").asText();
        } else {
            log.warn("暂未支持的图片 source 类型: {}", sourceType);
            return null;
        }

        ObjectNode imageUrl = objectMapper.createObjectNode();
        imageUrl.put("url", url);

        ObjectNode imagePart = objectMapper.createObjectNode();
        imagePart.put("type", "image_url");
        imagePart.set("image_url", imageUrl);
        return imagePart;
    }

    // ====== 工具（tools）相关转换 ======

    /**
     * 将 Anthropic tools 定义转换为 OpenAI function tools 格式。
     *
     * Anthropic 格式：[{name, description, input_schema:{type:"object", ...}}]
     * OpenAI 格式：[{type:"function", function:{name, description, parameters:{...}}}]
     */
    private ArrayNode convertTools(JsonNode anthropicTools) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode anthropicTool : anthropicTools) {
            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("type", "function");
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", anthropicTool.path("name").asText());
            function.put("description", anthropicTool.path("description").asText(""));
            function.set("parameters", anthropicTool.path("input_schema").isMissingNode()
                    ? objectMapper.createObjectNode() : anthropicTool.path("input_schema"));
            tool.set("function", function);
            tools.add(tool);
        }
        return tools;
    }

    /**
     * 将 Anthropic tool_choice 转换为 OpenAI tool_choice 格式。
     *
     * 映射关系：
     * - "none"/"auto" → "none"/"auto"（字符串）
     * - "any" → "required"（OpenAI 用 required 而非 any）
     * - {type:"tool", name:"..."} → {type:"function", function:{name:"..."}}
     */
    private JsonNode convertToolChoice(JsonNode toolChoiceNode) {
        if (toolChoiceNode == null || toolChoiceNode.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (toolChoiceNode.isTextual()) {
            return toolChoiceNode;
        }

        String type = toolChoiceNode.path("type").asText("auto");
        if ("none".equals(type) || "auto".equals(type)) {
            return JsonNodeFactory.instance.textNode(type);
        }
        if ("any".equals(type)) {
            // Anthropic "any" = OpenAI "required"
            return JsonNodeFactory.instance.textNode("required");
        }
        if ("tool".equals(type)) {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("type", "function");
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", toolChoiceNode.path("name").asText());
            result.set("function", function);
            return result;
        }

        return JsonNodeFactory.instance.textNode(type);
    }

    /**
     * 将 Anthropic tool_use 块转换为 OpenAI tool_calls 项。
     */
    private ObjectNode convertToolUseBlock(JsonNode block) {
        ObjectNode toolCall = objectMapper.createObjectNode();
        toolCall.put("id", block.path("id").asText());
        toolCall.put("type", "function");
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", block.path("name").asText());
        function.put("arguments", block.path("input").toString());
        toolCall.set("function", function);
        return toolCall;
    }

    /**
     * 将 Anthropic tool_result 块转换为 OpenAI role=tool 消息。
     *
     * 注意：OpenAI 的工具结果消息是独立的（不在 user 消息内），
     * 因此 convertToolResultBlock 返回的是一个完整消息，而非 content 块。
     */
    private ObjectNode convertToolResultBlock(JsonNode block) {
        ObjectNode toolMessage = objectMapper.createObjectNode();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", block.path("tool_use_id").asText());
        toolMessage.put("content", serializeToolResultContent(block));
        return toolMessage;
    }

    // ====== 响应转换辅助方法 ======

    /**
     * 将 OpenAI assistant 消息的 content 转为 Anthropic text blocks。
     *
     * 处理逻辑：
     * - 纯文本字符串 → 单个 text block
     * - 数组 → 遍历 parts，text part 转为 Anthropic text block
     * - 同时过滤掉 <think>...</think> 标签
     */
    private void appendAssistantContentBlocks(JsonNode contentNode, ArrayNode content) {
        if (contentNode == null || contentNode.isNull()) {
            return;
        }
        if (contentNode.isTextual()) {
            addTextBlock(content, stripReasoningText(contentNode.asText()));
            return;
        }
        if (!contentNode.isArray()) {
            addTextBlock(content, stripReasoningText(contentNode.toString()));
            return;
        }

        for (JsonNode part : contentNode) {
            String type = part.path("type").asText();
            if ("text".equals(type)) {
                addTextBlock(content, stripReasoningText(part.path("text").asText("")));
            } else {
                // 其他类型（如 image）转为字符串放入 text block
                addTextBlock(content, stripReasoningText(part.toString()));
            }
        }
    }

    /**
     * 将 OpenAI tool_calls 数组转为 Anthropic tool_use blocks。
     */
    private void appendToolUseBlocks(JsonNode toolCallsNode, ArrayNode content) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }
        for (JsonNode toolCall : toolCallsNode) {
            ObjectNode toolUse = objectMapper.createObjectNode();
            toolUse.put("type", "tool_use");
            toolUse.put("id", toolCall.path("id").asText());
            toolUse.put("name", toolCall.path("function").path("name").asText());
            toolUse.set("input", parseArguments(toolCall.path("function").path("arguments").asText("{}")));
            content.add(toolUse);
        }
    }

    /**
     * 添加一个 Anthropic text block 到 content 数组。
     */
    private void addTextBlock(ArrayNode content, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        content.add(textBlock);
    }

    /**
     * 从 Anthropic system/content 节点中提取纯文本内容。
     *
     * 处理情况：
     * - null/空 → 返回空字符串
     * - 字符串 → 直接返回
     * - 数组 → 遍历 elements，text 块累加文本，tool_result 递归提取
     */
    private String extractTextContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            Iterator<JsonNode> iterator = contentNode.elements();
            while (iterator.hasNext()) {
                JsonNode block = iterator.next();
                String blockType = block.path("type").asText();
                if ("text".equals(blockType)) {
                    builder.append(block.path("text").asText(""));
                } else if ("tool_result".equals(blockType)) {
                    // tool_result 的 content 也可能是文本
                    builder.append(extractTextContent(block.path("content")));
                } else if (block.isTextual()) {
                    builder.append(block.asText(""));
                }
            }
            return builder.toString();
        }
        return contentNode.toString();
    }

    /**
     * 序列化工具结果内容。
     *
     * 规则：
     * - 纯文本结果（null / 字符串 / 仅含 text 块的数组）→ 直接返回文本
     * - 复杂结构（多类型 blocks、含 is_error）→ 转为 JSON 对象字符串
     */
    private String serializeToolResultContent(JsonNode block) {
        JsonNode contentNode = block.path("content");
        boolean isError = block.path("is_error").asBoolean(false);
        if (!isError && isPlainTextToolResult(contentNode)) {
            return extractTextContent(contentNode);
        }

        ObjectNode structuredContent = objectMapper.createObjectNode();
        if (contentNode == null || contentNode.isNull() || contentNode.isMissingNode()) {
            structuredContent.put("content", "");
        } else if (contentNode.isTextual()) {
            structuredContent.put("content", contentNode.asText());
        } else {
            structuredContent.set("content", contentNode);
        }
        if (block.has("is_error")) {
            structuredContent.put("is_error", isError);
        }
        return structuredContent.toString();
    }

    /**
     * 判断工具结果是否为纯文本（不含复杂结构）。
     */
    private boolean isPlainTextToolResult(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull() || contentNode.isMissingNode()) {
            return true;
        }
        if (contentNode.isTextual()) {
            return true;
        }
        if (!contentNode.isArray()) {
            return false;
        }

        for (JsonNode block : contentNode) {
            if (!"text".equals(block.path("type").asText())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 转换 usage 信息，将 OpenAI 格式转为 Anthropic 格式。
     *
     * OpenAI：prompt_tokens + prompt_tokens_details.cached_tokens + completion_tokens
     * Anthropic：input_tokens + cache_read_input_tokens + output_tokens
     */
    private ObjectNode convertUsage(JsonNode usageNode) {
        ObjectNode usage = objectMapper.createObjectNode();
        int promptTokens = usageNode.path("prompt_tokens").asInt(0);
        int cachedTokens = usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        usage.put("input_tokens", Math.max(promptTokens - cachedTokens, 0));
        if (cachedTokens > 0) {
            usage.put("cache_read_input_tokens", cachedTokens);
        }
        usage.put("output_tokens", usageNode.path("completion_tokens").asInt(0));
        return usage;
    }

    /**
     * 解析 JSON 字符串为 JsonNode。若解析失败，返回包含原始字符串的 {"raw":"..."} 对象。
     */
    private JsonNode parseArguments(String rawArguments) {
        try {
            return objectMapper.readTree(rawArguments);
        } catch (Exception ex) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("raw", rawArguments);
            return wrapper;
        }
    }

    /**
     * 将 OpenAI finish_reason 映射为 Anthropic stop_reason。
     *
     * 映射表：
     * tool_calls / function_call → tool_use
     * length → max_tokens
     * content_filter → refusal
     * stop / null → end_turn
     * 其他 → 原样返回
     */
    private String mapFinishReason(String finishReason) {
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
            return "tool_use";
        }
        if ("length".equals(finishReason)) {
            return "max_tokens";
        }
        if ("content_filter".equals(finishReason)) {
            return "refusal";
        }
        if ("stop".equals(finishReason) || finishReason == null) {
            return "end_turn";
        }
        return finishReason;
    }

    /**
     * 从 OpenAI 错误响应中提取 message 字段。
     */
    private String extractErrorMessage(String openAiErrorBody) {
        try {
            JsonNode root = objectMapper.readTree(openAiErrorBody);
            JsonNode error = root.path("error");
            if (error.hasNonNull("message")) {
                return error.path("message").asText();
            }
            if (root.hasNonNull("message")) {
                return root.path("message").asText();
            }
        } catch (Exception ex) {
            log.warn("解析 OpenAI 错误报文失败，直接返回原始内容");
        }
        return openAiErrorBody;
    }

    /**
     * 根据 HTTP 状态码映射为 Anthropic 错误类型。
     */
    private String mapErrorType(int statusCode) {
        if (statusCode == 400) {
            return "invalid_request_error";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "authentication_error";
        }
        if (statusCode == 404) {
            return "not_found_error";
        }
        if (statusCode == 429) {
            return "rate_limit_error";
        }
        return "api_error";
    }

    /**
     * 过滤掉 <think>...</think> 思考过程标签，保留标签外的可见文本。
     *
     * 这是因为 Anthropic 模型生成的思考过程在 OpenAI 格式中是普通文本，
     * 转换回 Anthropic 响应时需要将其剔除。
     */
    private String stripReasoningText(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }

        StringBuilder visible = new StringBuilder();
        int start = 0;
        while (true) {
            int thinkStart = text.indexOf("<think>", start);
            if (thinkStart < 0) {
                visible.append(text.substring(start));
                break;
            }
            visible.append(text, start, thinkStart);
            int thinkEnd = text.indexOf("</think>", thinkStart + "<think>".length());
            if (thinkEnd < 0) {
                break;
            }
            start = thinkEnd + "</think>".length();
        }
        return visible.toString();
    }
}
