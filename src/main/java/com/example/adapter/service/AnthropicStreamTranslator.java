package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import com.example.adapter.util.SseWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 将 OpenAI Chat Completions 流式 SSE 事件实时翻译为 Anthropic Messages 流式 SSE 事件。
 *
 * <h2>背景：OpenAI SSE vs Anthropic SSE</h2>
 * 两者虽然都基于 SSE（Server-Sent Events），但事件结构和语义完全不同。
 *
 * <h3>OpenAI SSE 格式</h3>
 * 每个 chunk 是一行 JSON：
 * <pre>data: {"id":"chatcmpl-xxx","choices":[{"delta":{"content":"Hello"},"index":0}]}</pre>
 *
 * <h3>Anthropic SSE 格式</h3>
 * 包含多种事件类型，每种事件有独立的 event 行和 data 行：
 * <pre>
 * event: message_start
 * data: {"type":"message_start","message":{"id":"msg_xxx","type":"message",...}}
 *
 * event: content_block_start
 * data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
 *
 * event: content_block_delta
 * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
 *
 * event: message_delta
 * data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},...}
 *
 * event: message_stop
 * data: {"type":"message_stop"}
 * </pre>
 *
 * <h2>翻译策略</h2>
 * <ol>
 *   <li><b>消息开始</b>：首个 chunk 有 id/model，触发 message_start 事件</li>
 *   <li><b>文本增量</b>：delta.content → content_block_start → content_block_delta（文本块）</li>
 *   <li><b>工具调用</b>：delta.tool_calls[].id/name/arguments → content_block_start/tool_use + content_block_delta/input_json_delta</li>
 *   <li><b>并行工具</b>：通过 OpenAI 的 index 字段区分，映射到 Anthropic 的 block index</li>
 *   <li><b>消息结束</b>：收到 [DONE] 或流结束时，输出 message_delta + message_stop</li>
 * </ol>
 *
 * <h2>关键设计决策</h2>
 * <ul>
 *   <li>使用状态机（StreamState / ToolCallState）跟踪多块并行状态</li>
 *   <li>工具调用支持流式传输：id 和 name 先到，arguments 增量到达</li>
 *   <li></think> 思考过程通过 insideThinkBlock 状态机跨 chunk 过滤</li>
 *   <li>若上游未返回 [DONE]（某些实现可能省略），兜底调用 closeAnthropicStream</li>
 * </ul>
 *
 * @see SseWriter
 */
@Component
public class AnthropicStreamTranslator {

    private static final String THINK_OPEN_TAG = "<think>";
    private static final String THINK_CLOSE_TAG = "</think>";

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamTranslator.class);

    private final ObjectMapper objectMapper;
    private final ProxyProperties proxyProperties;

    public AnthropicStreamTranslator(ObjectMapper objectMapper, ProxyProperties proxyProperties) {
        this.objectMapper = objectMapper;
        this.proxyProperties = proxyProperties;
    }

    /**
     * 核心翻译方法：逐行读取上游 SSE，实时翻译并写入下游。
     *
     * <h3>循环逻辑</h3>
     * <pre>
     * while (reader.readLine() 非空):
     *   - 跳过非 data: 行（如注释、空行）
     *   - 解析 data: 后的 JSON
     *   - [DONE] → 关闭流并退出
     *   - 普通 chunk → processChunk 翻译
     *
     * 退出后兜底：
     *   - 若消息已开始但未正常结束 → 强制关闭流
     *   - 防止上游省略 [DONE] 时客户端永远等待
     * </pre>
     *
     * <h3>为什么不直接关闭 response？</h3>
     * 流式场景下，HTTP 连接需要保持打开直到所有数据发送完毕。
     * 这里通过写完所有 SSE 事件（含 message_stop）来正常结束流。
     *
     * @param reader        上游 SSE 输入流（OkHttp ResponseBody 的 BufferedReader）
     * @param outputStream  写入客户端的输出流（HttpServletResponse OutputStream）
     */
    public void translate(BufferedReader reader, OutputStream outputStream) throws IOException {
        StreamState state = new StreamState();
        String line;

        while ((line = reader.readLine()) != null) {
            // SSE 标准格式：跳过非 data: 开头的行（如空行、注释）
            if (!line.startsWith("data:")) {
                continue;
            }

            // 提取 data: 后的实际内容
            String data = line.substring("data:".length()).trim();
            if (!StringUtils.hasText(data)) {
                continue;
            }

            // 收到 [DONE] 表示上游流正常结束
            if ("[DONE]".equals(data)) {
                closeAnthropicStream(outputStream, state);
                state.messageStopped = true;
                break;
            }

            // 解析 OpenAI chunk JSON 并翻译为 Anthropic SSE 事件
            JsonNode chunk = objectMapper.readTree(data);
            processChunk(chunk, outputStream, state);
        }

        // 【兜底】若上游未发送 [DONE]（如某些实现省略），强制关闭流
        if (state.messageStarted && !state.messageStopped) {
            closeAnthropicStream(outputStream, state);
        }
        log.info("Anthropic SSE 翻译结束, finishReason={}", state.finishReason);
    }

    /**
     * 处理单个 OpenAI SSE chunk，提取文本/工具调用增量，发送对应的 Anthropic 事件。
     *
     * 处理顺序（重要：保持与 Anthropic 协议的事件顺序一致）：
     * <ol>
     *   <li>首次 chunk → 发送 message_start 事件</li>
     *   <li>delta.content 文本 → emitTextDelta（可能触发 content_block_start + content_block_delta）</li>
     *   <li>delta.tool_calls 数组 → emitToolDelta（可能触发 content_block_start + content_block_delta）</li>
     *   <li>记录 finish_reason 供最后输出</li>
     *   <li>flush 确保客户端实时收到</li>
     * </ol>
     */
    private void processChunk(JsonNode chunk, OutputStream outputStream, StreamState state) throws IOException {
        // OpenAI SSE 中 choices 是数组，取第一个 choice（通常只有一个）
        JsonNode choice = chunk.path("choices").isArray() && chunk.path("choices").size() > 0
                ? chunk.path("choices").get(0) : objectMapper.createObjectNode();
        JsonNode delta = choice.path("delta");

        // 从首个 chunk 中提取 message id 和 model
        if (StringUtils.hasText(chunk.path("id").asText())) {
            state.messageId = chunk.path("id").asText();
        }
        if (StringUtils.hasText(chunk.path("model").asText())) {
            state.model = chunk.path("model").asText();
            state.allowImplicitThinkingClose = supportsImplicitThinkingClose(state.model);
        }

        // 更新 token 使用量（OpenAI 在最后一个 chunk 的 usage 中提供）
        updateUsage(chunk.path("usage"), state);

        // 【首次 chunk】发送 message_start，Anthropic 协议要求此事件必须首先到达
        if (!state.messageStarted) {
            SseWriter.writeEvent(outputStream, "message_start", buildMessageStart(state));
            state.messageStarted = true;
        }

        // --- 文本内容增量 ---
        String rawText = delta.path("content").asText(null);
        if (rawText != null) {
            emitTextDelta(outputStream, state, rawText);
        }

        // --- 工具调用增量（可能多个工具并行） ---
        if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
            warnIfMiniMaxToolThinkingMayBeLost(state);
            for (JsonNode toolCallDelta : delta.get("tool_calls")) {
                emitToolDelta(outputStream, state, toolCallDelta);
            }
        }

        // 记录 finish_reason，但暂不输出（等待流结束时通过 message_delta 统一发送）
        if (!choice.path("finish_reason").isMissingNode() && !choice.path("finish_reason").isNull()) {
            state.finishReason = choice.path("finish_reason").asText();
        }

        // 每次处理完一个 chunk 后立即 flush，使客户端能实时看到输出
        outputStream.flush();
    }

    /**
     * 发射文本增量事件。
     *
     * 状态机逻辑：
     * <pre>
     * 若文本非空 且 当前无 text block：
     *   → 发送 content_block_start（type=text）
     *   → 标记 textBlockStarted = true
     * → 发送 content_block_delta（type=text_delta）
     * </pre>
     *
     * 关键：多个文本块理论上可能交替出现，但实际中通常只出现一个，
     * 因此使用简单的 started 标志。若需支持多文本块，可扩展 nextBlockIndex 逻辑。
     */
    private void emitTextDelta(OutputStream outputStream, StreamState state, String text) throws IOException {
        // 过滤掉 <think>...</think> 思考过程标签
        String visibleText = stripReasoningText(text, state);
        emitVisibleText(outputStream, state, visibleText);
    }

    /**
     * 发射工具调用增量事件。
     *
     * OpenAI 的工具调用增量可能分多次到达：
     * <pre>
     * chunk1: delta.tool_calls[0].id = "toolu_123", index=0
     * chunk2: delta.tool_calls[0].function.name = "get_weather", index=0
     * chunk3: delta.tool_calls[0].function.arguments = '{"city"', index=0
     * chunk4: delta.tool_calls[0].function.arguments = ':"Beijing"}', index=0
     * </pre>
     *
     * 本方法通过 ToolCallState 累积每个工具的状态：
     * - 首次到达（id 或 name）→ 发送 content_block_start（type=tool_use）
     * - 每次 arguments 有新增 → 发送 content_block_delta（type=input_json_delta）
     *
     * 并行工具支持：通过 openAiIndex → blockIndex 映射，允许多个工具块交错传输。
     */
    private void emitToolDelta(OutputStream outputStream, StreamState state, JsonNode toolCallDelta) throws IOException {
        dropPendingImplicitReasoningBeforeTool(state);

        // OpenAI 的 index 用于标识并发工具，映射到 Anthropic 的 block index
        int openAiIndex = toolCallDelta.path("index").asInt(0);
        ToolCallState toolState = state.tools.get(openAiIndex);
        if (toolState == null) {
            toolState = new ToolCallState();
            toolState.blockIndex = nextBlockIndex(state);
            state.tools.put(openAiIndex, toolState);
        }

        // 累积 id（工具调用 ID）
        if (toolCallDelta.hasNonNull("id")) {
            toolState.id = toolCallDelta.path("id").asText();
        }

        // 累积 function.name（工具名，可能分片到达，append 而非覆盖）
        if (toolCallDelta.path("function").hasNonNull("name")) {
            toolState.name += toolCallDelta.path("function").path("name").asText();
        }

        // 累积 function.arguments（JSON 参数，流式到达）
        String partialArguments = toolCallDelta.path("function").path("arguments").asText("");
        if (StringUtils.hasText(partialArguments)) {
            toolState.arguments.append(partialArguments);
        }

        // 【首次到达】id 或 name 已有值时，开启 tool_use content block
        if (!toolState.started && (StringUtils.hasText(toolState.id) || StringUtils.hasText(toolState.name))) {
            // 若之前有文本块，先关闭文本块（Anthropic 要求块之间有序）
            closeTextBlockIfNeeded(outputStream, state);
            SseWriter.writeEvent(outputStream, "content_block_start", buildToolBlockStart(toolState));
            toolState.started = true;
        }

        // 【边界情况】若只有 arguments 先到（无 id/name），也开启 block
        if (!toolState.started && StringUtils.hasText(partialArguments)) {
            closeTextBlockIfNeeded(outputStream, state);
            SseWriter.writeEvent(outputStream, "content_block_start", buildToolBlockStart(toolState));
            toolState.started = true;
        }

        // 【增量】arguments 有新增时，发送 input_json_delta
        // 注意：OpenAI 可能发送不完整的 JSON 片段，这是正常的设计（Claude 的 streaming 也如此）
        if (toolState.started && toolState.arguments.length() > toolState.emittedArgumentLength) {
            String newArguments = toolState.arguments.substring(toolState.emittedArgumentLength);
            toolState.emittedArgumentLength = toolState.arguments.length();
            SseWriter.writeEvent(outputStream, "content_block_delta",
                    buildToolDelta(toolState.blockIndex, newArguments));
        }
    }

    /**
     * 关闭流：发送剩余的 content_block_stop、message_delta 和 message_stop 事件。
     *
     * <h3>为什么需要这个方法？</h3>
     * - OpenAI SSE 中没有显式的 "block 结束" 事件
     * - Anthropic SSE 要求每个 content_block 都必须以 content_block_stop 结束
     * - 因此在流结束时，需要为所有开启过的块补发停止事件
     *
     * <h3>兜底场景</h3>
     * - 上游发送了 [DONE]
     * - 上游未发送 [DONE] 但流已结束（某些实现省略）
     */
    private void closeAnthropicStream(OutputStream outputStream, StreamState state) throws IOException {
        flushPendingLeadingTextAtStreamEnd(outputStream, state);

        // 关闭文本块（若还在进行中）
        if (state.textBlockStarted) {
            SseWriter.writeEvent(outputStream, "content_block_stop", buildContentBlockStop(state.textBlockIndex));
        }

        // 关闭所有工具块（确保每个工具都有 stop 事件）
        for (ToolCallState toolState : state.tools.values()) {
            if (!toolState.started) {
                // 工具只有 arguments 没有 id/name 的边界情况，补发 start
                SseWriter.writeEvent(outputStream, "content_block_start", buildToolBlockStart(toolState));
                toolState.started = true;
            }
            SseWriter.writeEvent(outputStream, "content_block_stop", buildContentBlockStop(toolState.blockIndex));
        }

        // message_delta：包含 stop_reason（由 OpenAI finish_reason 映射）和 usage
        SseWriter.writeEvent(outputStream, "message_delta", buildMessageDelta(state));

        // message_stop：Anthropic SSE 流的结束标志
        SseWriter.writeEvent(outputStream, "message_stop", objectWithType("message_stop"));
        outputStream.flush();
    }

    // ==================== 状态与事件构建 ====================

    /**
     * 获取下一个 block index。Anthropic 的 content blocks 按顺序编号（0, 1, 2...）。
     */
    private int nextBlockIndex(StreamState state) {
        return state.nextBlockIndex++;
    }

    /**
     * 从 OpenAI SSE chunk 中提取 usage 信息，累加到状态中。
     *
     * OpenAI 通常只在最后一个 chunk 提供完整 usage，
     * 但这里做累加处理以兼容分片提供的实现。
     */
    private void updateUsage(JsonNode usageNode, StreamState state) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return;
        }
        int promptTokens = usageNode.path("prompt_tokens").asInt(state.inputTokens + state.cacheReadInputTokens);
        state.cacheReadInputTokens = usageNode.path("prompt_tokens_details").path("cached_tokens")
                .asInt(state.cacheReadInputTokens);
        state.inputTokens = Math.max(promptTokens - state.cacheReadInputTokens, 0);
        state.outputTokens = usageNode.path("completion_tokens").asInt(state.outputTokens);
    }

    /**
     * 构建 message_start 事件。
     *
     * Anthropic message_start 包含完整的 message 对象：
     * - id, type, role, model
     * - content（空数组，等待 content_block_start 填充）
     * - stop_reason（null，等待 message_delta 时填充）
     * - usage（从 OpenAI usage 映射）
     */
    private ObjectNode buildMessageStart(StreamState state) {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input_tokens", state.inputTokens);
        usage.put("output_tokens", state.outputTokens);
        if (state.cacheReadInputTokens > 0) {
            usage.put("cache_read_input_tokens", state.cacheReadInputTokens);
        }

        ObjectNode message = objectMapper.createObjectNode();
        message.put("id", state.messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", state.model);
        message.set("content", objectMapper.createArrayNode());
        message.putNull("stop_reason");
        message.putNull("stop_sequence");
        message.set("usage", usage);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message_start");
        event.set("message", message);
        return event;
    }

    /**
     * 构建 content_block_start（type=text）事件。
     */
    private ObjectNode buildTextBlockStart(int index) {
        ObjectNode contentBlock = objectMapper.createObjectNode();
        contentBlock.put("type", "text");
        contentBlock.put("text", "");

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        event.set("content_block", contentBlock);
        return event;
    }

    /**
     * 构建 content_block_start（type=tool_use）事件。
     *
     * 若工具的 id 尚未到达（只有 name 和 arguments），生成一个临时 id。
     */
    private ObjectNode buildToolBlockStart(ToolCallState toolState) {
        ObjectNode contentBlock = objectMapper.createObjectNode();
        contentBlock.put("type", "tool_use");
        contentBlock.put("id", StringUtils.hasText(toolState.id)
                ? toolState.id : "toolu_" + UUID.randomUUID().toString().replace("-", ""));
        contentBlock.put("name", toolState.name);
        contentBlock.set("input", objectMapper.createObjectNode());

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", toolState.blockIndex);
        event.set("content_block", contentBlock);
        return event;
    }

    /**
     * 构建 content_block_delta（type=text_delta）事件。
     */
    private ObjectNode buildTextDelta(int index, String text) {
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("type", "text_delta");
        delta.put("text", text);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        event.set("delta", delta);
        return event;
    }

    /**
     * 构建 content_block_delta（type=input_json_delta）事件。
     *
     * Anthropic 使用 partial_json 字段接收 JSON 参数的增量片段，
     * 这是流式工具调用的标准方式，允许在参数尚未完整时就实时展示。
     */
    private ObjectNode buildToolDelta(int index, String partialJson) {
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", partialJson);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        event.set("delta", delta);
        return event;
    }

    /**
     * 构建 content_block_stop 事件（无 data payload，仅 index）。
     */
    private ObjectNode buildContentBlockStop(int index) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_stop");
        event.put("index", index);
        return event;
    }

    /**
     * 构建 message_delta 事件，包含 stop_reason 和最终 usage。
     */
    private ObjectNode buildMessageDelta(StreamState state) {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input_tokens", state.inputTokens);
        usage.put("output_tokens", state.outputTokens);
        if (state.cacheReadInputTokens > 0) {
            usage.put("cache_read_input_tokens", state.cacheReadInputTokens);
        }

        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("stop_reason", mapFinishReason(state.finishReason));
        delta.putNull("stop_sequence");

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message_delta");
        event.set("delta", delta);
        event.set("usage", usage);
        return event;
    }

    private ObjectNode objectWithType(String type) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        return node;
    }

    /**
     * OpenAI finish_reason → Anthropic stop_reason 映射。
     */
    private String mapFinishReason(String finishReason) {
        if ("length".equals(finishReason)) {
            return "max_tokens";
        }
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
            return "tool_use";
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
     * 关闭当前文本块（在切换到工具块之前调用）。
     *
     * Anthropic SSE 要求块之间有序，不能出现 text 的 delta 出现在 tool_use 的 start 之后。
     */
    private void closeTextBlockIfNeeded(OutputStream outputStream, StreamState state) throws IOException {
        if (!state.textBlockStarted) {
            return;
        }
        SseWriter.writeEvent(outputStream, "content_block_stop", buildContentBlockStop(state.textBlockIndex));
        state.textBlockStarted = false;
        state.textBlockIndex = -1;
    }

    private boolean hasVisibleCharacters(String text) {
        return text != null && text.trim().length() > 0;
    }

    private void emitVisibleText(OutputStream outputStream, StreamState state, String visibleText) throws IOException {
        // 无可见字符时：若已开启文本块则跳过，若未开启也跳过（避免空 block）
        if (!StringUtils.hasText(visibleText) && !hasVisibleCharacters(visibleText)) {
            return;
        }
        if (!state.textBlockStarted && !hasVisibleCharacters(visibleText)) {
            return;
        }

        if (!state.textBlockStarted) {
            state.textBlockIndex = nextBlockIndex(state);
            SseWriter.writeEvent(outputStream, "content_block_start", buildTextBlockStart(state.textBlockIndex));
            state.textBlockStarted = true;
        }

        SseWriter.writeEvent(outputStream, "content_block_delta", buildTextDelta(state.textBlockIndex, visibleText));
    }

    /**
     * 过滤 <think>...</think> 思考过程标签，支持跨 chunk 连续过滤。
     *
     * 状态机：遇到 <think> → 进入 insideThinkBlock 模式，跳过所有文本；
     * 遇到</think> → 退出模式，继续输出。
     *
     * 由于 OpenAI 的 delta.content 可能包含不完整的 <think> 标签，
     * 使用状态机而非简单的 indexOf 可以正确处理跨 chunk 边界的标签。
     */
    private String stripReasoningText(String text, StreamState state) {
        if (text == null || text.length() == 0) {
            return "";
        }
        if (!proxyProperties.isFilterReasoningText()) {
            return text;
        }
        if (state.allowImplicitThinkingClose && !state.leadingThinkBoundaryResolved && !state.insideThinkBlock) {
            state.pendingLeadingText.append(text);
            return resolvePendingLeadingText(state);
        }

        return stripExplicitReasoningText(text, state);
    }

    private String resolvePendingLeadingText(StreamState state) {
        String bufferedText = state.pendingLeadingText.toString();
        int thinkStart = bufferedText.indexOf(THINK_OPEN_TAG);
        int thinkEnd = bufferedText.indexOf(THINK_CLOSE_TAG);
        if (thinkEnd >= 0 && (thinkStart < 0 || thinkEnd < thinkStart)) {
            state.leadingThinkBoundaryResolved = true;
            state.pendingLeadingText.setLength(0);
            return stripExplicitReasoningText(bufferedText.substring(thinkEnd + THINK_CLOSE_TAG.length()), state);
        }
        if (thinkStart >= 0) {
            state.leadingThinkBoundaryResolved = true;
            state.pendingLeadingText.setLength(0);
            return stripExplicitReasoningText(bufferedText, state);
        }
        return "";
    }

    private String stripExplicitReasoningText(String text, StreamState state) {
        StringBuilder visible = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (state.insideThinkBlock) {
                // 寻找</think> 结束标签
                int thinkEnd = text.indexOf(THINK_CLOSE_TAG, index);
                if (thinkEnd < 0) {
                    // 整段都是思考内容，全部丢弃
                    return visible.toString();
                }
                state.insideThinkBlock = false;
                index = thinkEnd + THINK_CLOSE_TAG.length();
                continue;
            }

            // 寻找下一个<think> 起始标签
            int thinkStart = text.indexOf(THINK_OPEN_TAG, index);
            if (thinkStart < 0) {
                visible.append(text.substring(index));
                break;
            }

            visible.append(text, index, thinkStart);
            state.insideThinkBlock = true;
            index = thinkStart + THINK_OPEN_TAG.length();
        }
        return visible.toString();
    }

    private void flushPendingLeadingTextAtStreamEnd(OutputStream outputStream, StreamState state) throws IOException {
        if (state.pendingLeadingText.length() == 0) {
            return;
        }
        if (state.leadingThinkBoundaryResolved || state.insideThinkBlock) {
            state.pendingLeadingText.setLength(0);
            return;
        }

        String pendingText = state.pendingLeadingText.toString();
        state.pendingLeadingText.setLength(0);
        state.leadingThinkBoundaryResolved = true;
        emitVisibleText(outputStream, state, pendingText);
    }

    private void dropPendingImplicitReasoningBeforeTool(StreamState state) {
        if (!state.allowImplicitThinkingClose || state.leadingThinkBoundaryResolved
                || state.pendingLeadingText.length() == 0) {
            return;
        }
        state.pendingLeadingText.setLength(0);
        state.leadingThinkBoundaryResolved = true;
    }

    private boolean supportsImplicitThinkingClose(String model) {
        String normalizedModel = model == null ? "" : model.toLowerCase();
        return normalizedModel.contains("glm-4.5")
                || normalizedModel.contains("glm-4.6")
                || normalizedModel.contains("glm-4.7");
    }

    private void warnIfMiniMaxToolThinkingMayBeLost(StreamState state) {
        if (state.reasoningFilterWarningLogged || !proxyProperties.isFilterReasoningText() || !isMiniMaxM2Model(state.model)) {
            return;
        }
        state.reasoningFilterWarningLogged = true;
        log.warn("检测到 MiniMax M2 流式工具调用，当前仍在过滤 reasoning 文本。"
                        + "这会丢失 interleaved thinking 上下文，可能导致后续 agent/tool 回合异常。"
                        + "如需验证，请将 FILTER_REASONING_TEXT=false。model={}",
                state.model);
    }

    private boolean isMiniMaxM2Model(String model) {
        String normalizedModel = model == null ? "" : model.toLowerCase();
        return normalizedModel.contains("minimax-m2");
    }

    /**
     * SSE 流式翻译的全局状态。
     *
     * 包含：消息是否已开始/停止、当前文本块状态、token 计数、工具调用映射表。
     */
    private static class StreamState {
        /** 是否已发送 message_start 事件 */
        private boolean messageStarted;
        /** 是否已发送 message_stop 事件 */
        private boolean messageStopped;
        /** 是否处于 <think>...</think> 思考过程标签内部 */
        private boolean insideThinkBlock;
        /** 是否需要兼容 GLM 模板注入的隐式 <think> 起始标签 */
        private boolean allowImplicitThinkingClose;
        /** 流开头的思考边界是否已经确定 */
        private boolean leadingThinkBoundaryResolved;
        /** 等待确定是否属于隐式思考块的前缀文本 */
        private final StringBuilder pendingLeadingText = new StringBuilder();
        /** 是否已经打印过 MiniMax 工具调用的 reasoning 过滤告警 */
        private boolean reasoningFilterWarningLogged;
        /** 当前是否有开启的文本块（需在切换到工具块前关闭） */
        private boolean textBlockStarted;
        /** 当前文本块的 Anthropic block index */
        private int textBlockIndex = -1;
        /** 下一个可用的 block index（递增） */
        private int nextBlockIndex;
        /** 输入 token 数（累加） */
        private int inputTokens;
        /** 输出 token 数（累加） */
        private int outputTokens;
        /** 缓存命中的输入 token 数 */
        private int cacheReadInputTokens;
        /** Anthropic message ID（从 OpenAI 映射） */
        private String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        /** 模型名称 */
        private String model = "";
        /** 停止原因（从 OpenAI finish_reason 映射） */
        private String finishReason;
        /** OpenAI tool index → Anthropic block index 映射表 */
        private final Map<Integer, ToolCallState> tools = new LinkedHashMap<Integer, ToolCallState>();
    }

    /**
     * 单个工具调用的流式翻译状态。
     *
     * OpenAI 的工具调用参数是流式传输的（arguments 分多次到达），
     * 因此需要累积已收到的内容。
     */
    private static class ToolCallState {
        /** 是否已发送 content_block_start */
        private boolean started;
        /** Anthropic block index */
        private int blockIndex;
        /** 工具调用 ID（可能比 name 晚到达） */
        private String id = "";
        /** 工具名称（可能分片到达） */
        private String name = "";
        /** 已发送的 arguments 长度（用于计算新增部分） */
        private int emittedArgumentLength;
        /** 累积的完整 arguments（可能是非完整 JSON） */
        private final StringBuilder arguments = new StringBuilder();
    }
}
