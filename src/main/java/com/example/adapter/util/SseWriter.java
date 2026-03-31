package com.example.adapter.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SSE（Server-Sent Events）事件写入工具类。
 *
 * <h2>SSE 格式</h2>
 * SSE 每条事件由两行组成，以双换行符结尾：
 * <pre>
 * event: &lt;event-name&gt;
 * data: &lt;json-data&gt;
 *
 * </pre>
 *
 * <h2>示例</h2>
 * <pre>
 * event: message_start
 * data: {"type":"message_start","message":{...}}
 *
 * </pre>
 *
 * <h2>为什么要用这个工具？</h2>
 * SSE 对格式有严格要求（event 行、data 行、双换行分隔）。
 * 手动拼接容易出错（如换行符遗漏或多余），
 * 本工具确保格式正确且与 Anthropic SSE 协议一致。
 *
 * @see com.example.adapter.service.AnthropicStreamTranslator
 */
public final class SseWriter {

    private SseWriter() {
    }

    /**
     * 向输出流写入一条 SSE 事件。
     *
     * @param outputStream 目标输出流（HttpServletResponse 的 OutputStream）
     * @param eventName    事件类型名（如 "message_start"、"content_block_delta"）
     * @param data         事件数据（JsonNode，会调用 toString()）
     */
    public static void writeEvent(OutputStream outputStream, String eventName, JsonNode data) throws IOException {
        // 严格遵循 SSE 格式：event:xxx\ndata:xxx\n\n
        String payload = "event: " + eventName + "\n" +
                "data: " + data.toString() + "\n\n";
        outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
    }
}
