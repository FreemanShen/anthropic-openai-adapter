package com.example.adapter.service;

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
 * 将 OpenAI Chat Completions 的增量 SSE 事件翻译为 Anthropic Messages SSE 事件。
 * 重点处理文本增量、工具调用增量、多工具并行和上游未返回 [DONE] 时的兜底收尾。
 */
@Component
public class AnthropicStreamTranslator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamTranslator.class);

    private final ObjectMapper objectMapper;

    public AnthropicStreamTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void translate(BufferedReader reader, OutputStream outputStream) throws IOException {
        StreamState state = new StreamState();
        String line;

        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }

            String data = line.substring("data:".length()).trim();
            if (!StringUtils.hasText(data)) {
                continue;
            }
            if ("[DONE]".equals(data)) {
                closeAnthropicStream(outputStream, state);
                state.messageStopped = true;
                break;
            }

            JsonNode chunk = objectMapper.readTree(data);
            processChunk(chunk, outputStream, state);
        }

        if (state.messageStarted && !state.messageStopped) {
            closeAnthropicStream(outputStream, state);
        }
        log.info("Anthropic SSE 翻译结束, finishReason={}", state.finishReason);
    }

    private void processChunk(JsonNode chunk, OutputStream outputStream, StreamState state) throws IOException {
        JsonNode choice = chunk.path("choices").isArray() && chunk.path("choices").size() > 0
                ? chunk.path("choices").get(0) : objectMapper.createObjectNode();
        JsonNode delta = choice.path("delta");

        if (StringUtils.hasText(chunk.path("id").asText())) {
            state.messageId = chunk.path("id").asText();
        }
        if (StringUtils.hasText(chunk.path("model").asText())) {
            state.model = chunk.path("model").asText();
        }
        updateUsage(chunk.path("usage"), state);

        if (!state.messageStarted) {
            SseWriter.writeEvent(outputStream, "message_start", buildMessageStart(state));
            state.messageStarted = true;
        }

        String rawText = delta.path("content").asText(null);
        if (rawText != null) {
            emitTextDelta(outputStream, state, rawText);
        }

        if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
            for (JsonNode toolCallDelta : delta.get("tool_calls")) {
                emitToolDelta(outputStream, state, toolCallDelta);
            }
        }

        if (!choice.path("finish_reason").isMissingNode() && !choice.path("finish_reason").isNull()) {
            state.finishReason = choice.path("finish_reason").asText();
        }
        outputStream.flush();
    }

    private void emitTextDelta(OutputStream outputStream, StreamState state, String text) throws IOException {
        String visibleText = stripReasoningText(text, state);
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

    private void emitToolDelta(OutputStream outputStream, StreamState state, JsonNode toolCallDelta) throws IOException {
        int openAiIndex = toolCallDelta.path("index").asInt(0);
        ToolCallState toolState = state.tools.get(openAiIndex);
        if (toolState == null) {
            toolState = new ToolCallState();
            toolState.blockIndex = nextBlockIndex(state);
            state.tools.put(openAiIndex, toolState);
        }

        if (toolCallDelta.hasNonNull("id")) {
            toolState.id = toolCallDelta.path("id").asText();
        }
        if (toolCallDelta.path("function").hasNonNull("name")) {
            toolState.name += toolCallDelta.path("function").path("name").asText();
        }
        String partialArguments = toolCallDelta.path("function").path("arguments").asText("");
        if (StringUtils.hasText(partialArguments)) {
            toolState.arguments.append(partialArguments);
        }

        if (!toolState.started && (StringUtils.hasText(toolState.id) || StringUtils.hasText(toolState.name))) {
            closeTextBlockIfNeeded(outputStream, state);
            SseWriter.writeEvent(outputStream, "content_block_start", buildToolBlockStart(toolState));
            toolState.started = true;
        }

        if (!toolState.started && StringUtils.hasText(partialArguments)) {
            closeTextBlockIfNeeded(outputStream, state);
            SseWriter.writeEvent(outputStream, "content_block_start", buildToolBlockStart(toolState));
            toolState.started = true;
        }

        if (toolState.started && toolState.arguments.length() > toolState.emittedArgumentLength) {
            String newArguments = toolState.arguments.substring(toolState.emittedArgumentLength);
            toolState.emittedArgumentLength = toolState.arguments.length();
            SseWriter.writeEvent(outputStream, "content_block_delta",
                    buildToolDelta(toolState.blockIndex, newArguments));
        }
    }

    private void closeAnthropicStream(OutputStream outputStream, StreamState state) throws IOException {
        if (state.textBlockStarted) {
            SseWriter.writeEvent(outputStream, "content_block_stop", buildContentBlockStop(state.textBlockIndex));
        }

        for (ToolCallState toolState : state.tools.values()) {
            if (!toolState.started) {
                SseWriter.writeEvent(outputStream, "content_block_start", buildToolBlockStart(toolState));
                toolState.started = true;
            }
            SseWriter.writeEvent(outputStream, "content_block_stop", buildContentBlockStop(toolState.blockIndex));
        }

        SseWriter.writeEvent(outputStream, "message_delta", buildMessageDelta(state));
        SseWriter.writeEvent(outputStream, "message_stop", objectWithType("message_stop"));
        outputStream.flush();
    }

    private int nextBlockIndex(StreamState state) {
        return state.nextBlockIndex++;
    }

    private void updateUsage(JsonNode usageNode, StreamState state) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return;
        }
        int promptTokens = usageNode.path("prompt_tokens").asInt(state.inputTokens + state.cacheReadInputTokens);
        state.cacheReadInputTokens = usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(state.cacheReadInputTokens);
        state.inputTokens = Math.max(promptTokens - state.cacheReadInputTokens, 0);
        state.outputTokens = usageNode.path("completion_tokens").asInt(state.outputTokens);
    }

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

    private ObjectNode buildContentBlockStop(int index) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_stop");
        event.put("index", index);
        return event;
    }

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

    private String stripReasoningText(String text, StreamState state) {
        if (text == null || text.length() == 0) {
            return "";
        }

        StringBuilder visible = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (state.insideThinkBlock) {
                int thinkEnd = text.indexOf("</think>", index);
                if (thinkEnd < 0) {
                    return visible.toString();
                }
                state.insideThinkBlock = false;
                index = thinkEnd + "</think>".length();
                continue;
            }

            int thinkStart = text.indexOf("<think>", index);
            if (thinkStart < 0) {
                visible.append(text.substring(index));
                break;
            }

            visible.append(text, index, thinkStart);
            state.insideThinkBlock = true;
            index = thinkStart + "<think>".length();
        }
        return visible.toString();
    }

    private static class StreamState {
        private boolean messageStarted;
        private boolean messageStopped;
        private boolean insideThinkBlock;
        private boolean textBlockStarted;
        private int textBlockIndex = -1;
        private int nextBlockIndex;
        private int inputTokens;
        private int outputTokens;
        private int cacheReadInputTokens;
        private String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        private String model = "";
        private String finishReason;
        private final Map<Integer, ToolCallState> tools = new LinkedHashMap<Integer, ToolCallState>();
    }

    private static class ToolCallState {
        private boolean started;
        private int blockIndex;
        private String id = "";
        private String name = "";
        private int emittedArgumentLength;
        private final StringBuilder arguments = new StringBuilder();
    }
}
