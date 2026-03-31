package com.example.adapter.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 统一输出 SSE 事件格式。
 */
public final class SseWriter {

    private SseWriter() {
    }

    public static void writeEvent(OutputStream outputStream, String eventName, JsonNode data) throws IOException {
        String payload = "event: " + eventName + "\n" +
                "data: " + data.toString() + "\n\n";
        outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
    }
}
