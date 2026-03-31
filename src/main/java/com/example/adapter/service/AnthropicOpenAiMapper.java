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
 * 负责 Anthropic Messages 与 OpenAI Chat Completions 之间的双向转换。
 * 这里尽量覆盖文本、图片、工具调用、工具返回、停止条件和错误报文。
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

    public JsonNode toOpenAiRequest(JsonNode anthropicRequest) {
        ObjectNode openAiRequest = objectMapper.createObjectNode();
        openAiRequest.put("model", resolveModel(anthropicRequest));
        boolean stream = anthropicRequest.path("stream").asBoolean(false);
        openAiRequest.put("stream", stream);

        copyIfPresent(anthropicRequest, openAiRequest, "max_tokens", "max_tokens");
        copyIfPresent(anthropicRequest, openAiRequest, "temperature", "temperature");
        copyIfPresent(anthropicRequest, openAiRequest, "top_p", "top_p");
        copyIfPresent(anthropicRequest, openAiRequest, "metadata", "metadata");
        if (stream) {
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            openAiRequest.set("stream_options", streamOptions);
        }

        if (anthropicRequest.has("stop_sequences") && anthropicRequest.get("stop_sequences").isArray()) {
            openAiRequest.set("stop", anthropicRequest.get("stop_sequences"));
        }

        if (anthropicRequest.has("metadata") && anthropicRequest.path("metadata").hasNonNull("user_id")) {
            openAiRequest.put("user", anthropicRequest.path("metadata").path("user_id").asText());
        }

        ArrayNode messages = objectMapper.createArrayNode();
        appendSystemMessages(anthropicRequest.get("system"), messages);
        appendConversationMessages(anthropicRequest.path("messages"), messages);
        openAiRequest.set("messages", messages);

        if (anthropicRequest.has("tools")) {
            openAiRequest.set("tools", convertTools(anthropicRequest.get("tools")));
        }
        if (anthropicRequest.has("tool_choice")) {
            JsonNode toolChoice = anthropicRequest.get("tool_choice");
            openAiRequest.set("tool_choice", convertToolChoice(toolChoice));
            if (toolChoice.path("disable_parallel_tool_use").asBoolean(false)) {
                openAiRequest.put("parallel_tool_calls", false);
            }
        }
        return openAiRequest;
    }

    public JsonNode toAnthropicResponse(String openAiResponseBody) throws JsonProcessingException {
        JsonNode openAiResponse = objectMapper.readTree(openAiResponseBody);
        ObjectNode anthropicResponse = baseAnthropicMessage(openAiResponse);

        JsonNode choice = firstChoice(openAiResponse);
        JsonNode message = choice.path("message");
        ArrayNode content = objectMapper.createArrayNode();

        appendAssistantContentBlocks(message.path("content"), content);
        appendToolUseBlocks(message.path("tool_calls"), content);

        anthropicResponse.set("content", content);
        anthropicResponse.put("stop_reason", mapFinishReason(choice.path("finish_reason").asText(null)));
        anthropicResponse.putNull("stop_sequence");
        anthropicResponse.set("usage", convertUsage(openAiResponse.path("usage")));
        return anthropicResponse;
    }

    public JsonNode toAnthropicError(String openAiErrorBody, int statusCode) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", "error");

        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("type", mapErrorType(statusCode));
        detail.put("message", extractErrorMessage(openAiErrorBody));
        error.set("error", detail);
        return error;
    }

    private ObjectNode baseAnthropicMessage(JsonNode openAiResponse) {
        ObjectNode anthropicResponse = objectMapper.createObjectNode();
        anthropicResponse.put("id", openAiResponse.path("id").asText("msg_adapter"));
        anthropicResponse.put("type", "message");
        anthropicResponse.put("role", "assistant");
        anthropicResponse.put("model", openAiResponse.path("model").asText(proxyProperties.getDefaultModel()));
        return anthropicResponse;
    }

    private JsonNode firstChoice(JsonNode openAiResponse) {
        JsonNode choices = openAiResponse.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0);
        }
        return objectMapper.createObjectNode();
    }

    private String resolveModel(JsonNode anthropicRequest) {
        if (anthropicRequest.hasNonNull("model")) {
            return anthropicRequest.get("model").asText();
        }
        return proxyProperties.getDefaultModel();
    }

    private void copyIfPresent(JsonNode from, ObjectNode to, String sourceField, String targetField) {
        if (from.has(sourceField)) {
            to.set(targetField, from.get(sourceField));
        }
    }

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
            if (contentNode.isTextual()) {
                messages.add(simpleMessage(role, contentNode.asText()));
                continue;
            }
            if (!contentNode.isArray()) {
                log.warn("检测到不支持的 content 结构, role={}", role);
                continue;
            }

            if ("assistant".equals(role)) {
                appendAssistantMessage(contentNode, messages);
            } else {
                appendNonAssistantMessage(role, contentNode, messages);
            }
        }
    }

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
                messages.add(convertToolResultBlock(block));
            } else {
                log.warn("暂未支持的 {} content block 类型: {}", role, blockType);
            }
        }

        if (messageContent.size() > 0) {
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", role);
            setOpenAiContent(userMessage, messageContent);
            messages.add(userMessage);
        }
    }

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

    private ObjectNode simpleMessage(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private JsonNode buildOpenAiTextPart(String text) {
        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", text);
        return textPart;
    }

    private JsonNode buildOpenAiImagePart(JsonNode block) {
        JsonNode source = block.path("source");
        String sourceType = source.path("type").asText();
        String mediaType = source.path("media_type").asText("image/png");
        String url = null;

        if ("base64".equals(sourceType) && source.hasNonNull("data")) {
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

    private ObjectNode convertToolResultBlock(JsonNode block) {
        ObjectNode toolMessage = objectMapper.createObjectNode();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", block.path("tool_use_id").asText());
        toolMessage.put("content", serializeToolResultContent(block));
        return toolMessage;
    }

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
                addTextBlock(content, stripReasoningText(part.toString()));
            }
        }
    }

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

    private void addTextBlock(ArrayNode content, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        content.add(textBlock);
    }

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
                    builder.append(extractTextContent(block.path("content")));
                } else if (block.isTextual()) {
                    builder.append(block.asText(""));
                }
            }
            return builder.toString();
        }
        return contentNode.toString();
    }

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

    private JsonNode parseArguments(String rawArguments) {
        try {
            return objectMapper.readTree(rawArguments);
        } catch (Exception ex) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("raw", rawArguments);
            return wrapper;
        }
    }

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
