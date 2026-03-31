package com.example.adapter.logging;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class LogSanitizerTest {

    @Test
    void shouldTruncateLongPayloads() {
        String input = "1234567890";
        String output = LogSanitizer.truncate(input, 5);
        Assertions.assertTrue(output.startsWith("12345"));
        Assertions.assertTrue(output.contains("truncated"));
    }

    @Test
    void shouldBuildBodyPreviewFromBytes() {
        byte[] payload = "hello\nworld".getBytes(StandardCharsets.UTF_8);
        Assertions.assertEquals("hello\nworld", LogSanitizer.bodyPreview(payload, "UTF-8"));
    }
}
