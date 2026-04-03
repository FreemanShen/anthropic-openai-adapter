package com.example.adapter.logging;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

class BoundedPreviewOutputStreamTest {

    @Test
    void shouldCapturePreviewWithoutTruncation() throws Exception {
        ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        BoundedPreviewOutputStream outputStream = new BoundedPreviewOutputStream(delegate, 32);

        outputStream.write("hello".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        Assertions.assertEquals("hello", delegate.toString("UTF-8"));
        Assertions.assertEquals("hello", outputStream.preview("UTF-8"));
        Assertions.assertTrue(outputStream.hasCapturedContent());
    }

    @Test
    void shouldMarkPreviewAsTruncatedWhenPayloadExceedsLimit() throws Exception {
        ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        BoundedPreviewOutputStream outputStream = new BoundedPreviewOutputStream(delegate, 5);

        outputStream.write("hello world".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals("hello world", delegate.toString("UTF-8"));
        Assertions.assertTrue(outputStream.preview("UTF-8").startsWith("hello"));
        Assertions.assertTrue(outputStream.preview("UTF-8").contains("truncated"));
    }
}
