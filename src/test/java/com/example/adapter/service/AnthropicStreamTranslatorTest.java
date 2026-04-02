package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

class AnthropicStreamTranslatorTest {

    @Test
    void shouldTranslateTextAndToolCallStream() throws Exception {
        String sse = ""
                + "data: {\"id\":\"chatcmpl-1\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"先查一下\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Shanghai\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":12}}\n\n"
                + "data: [DONE]\n\n";

        AnthropicStreamTranslator translator = createTranslator(true);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        translator.translate(new BufferedReader(new StringReader(sse)), outputStream);
        String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);

        Assertions.assertTrue(output.contains("event: message_start"));
        Assertions.assertTrue(output.contains("\"type\":\"text_delta\""));
        Assertions.assertTrue(output.contains("\"type\":\"tool_use\""));
        Assertions.assertTrue(output.contains("\"partial_json\":\"{\\\"city\\\":\""));
        Assertions.assertTrue(output.contains("\"partial_json\":\"\\\"Shanghai\\\"}\""));
        Assertions.assertTrue(output.contains("\"stop_reason\":\"tool_use\""));
        Assertions.assertTrue(output.contains("event: message_stop"));
    }

    @Test
    void shouldStripThinkBlocksCloseTextBeforeToolAndUseFinalUsageChunk() throws Exception {
        String sse = ""
                + "data: {\"id\":\"chatcmpl-2\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-2\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"<think>\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-2\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hidden\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-2\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"</think>Visible text\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-2\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"\"}}]}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-2\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\\\"Shanghai\\\"}\"}}]},\"finish_reason\":\"content_filter\"}],\"usage\":null}\n\n"
                + "data: {\"id\":\"chatcmpl-2\",\"model\":\"MiniMax-M2.1\",\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9,\"prompt_tokens_details\":{\"cached_tokens\":4}}}\n\n";

        AnthropicStreamTranslator translator = createTranslator(true);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        translator.translate(new BufferedReader(new StringReader(sse)), outputStream);
        String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);

        Assertions.assertFalse(output.contains("hidden"));
        Assertions.assertFalse(output.contains("<think>"));
        Assertions.assertTrue(output.contains("\"text\":\"Visible text\""));
        Assertions.assertTrue(output.contains("\"cache_read_input_tokens\":4"));
        Assertions.assertTrue(output.contains("\"output_tokens\":9"));
        Assertions.assertTrue(output.contains("\"stop_reason\":\"refusal\""));

        int textStart = output.indexOf("\"content_block\":{\"type\":\"text\"");
        int textStop = output.indexOf("event: content_block_stop", textStart);
        int toolStart = output.indexOf("\"content_block\":{\"type\":\"tool_use\"");
        Assertions.assertTrue(textStart >= 0);
        Assertions.assertTrue(textStop > textStart);
        Assertions.assertTrue(toolStart > textStop);
    }

    @Test
    void shouldStripImplicitReasoningPrefixForGlmStream() throws Exception {
        String sse = ""
                + "data: {\"id\":\"chatcmpl-4\",\"model\":\"glm-4.7-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-4\",\"model\":\"glm-4.7-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hidden reasoning \"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-4\",\"model\":\"glm-4.7-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"still hidden\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-4\",\"model\":\"glm-4.7-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"</think>Visible text\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":4}}\n\n"
                + "data: [DONE]\n\n";

        AnthropicStreamTranslator translator = createTranslator(true);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        translator.translate(new BufferedReader(new StringReader(sse)), outputStream);
        String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);

        Assertions.assertFalse(output.contains("hidden reasoning"));
        Assertions.assertFalse(output.contains("still hidden"));
        Assertions.assertTrue(output.contains("\"text\":\"Visible text\""));
        Assertions.assertTrue(output.contains("\"stop_reason\":\"end_turn\""));
    }

    @Test
    void shouldKeepPlainTextForNonThinkingStreamWithoutThinkTags() throws Exception {
        String sse = ""
                + "data: {\"id\":\"chatcmpl-5\",\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-5\",\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Visible text only\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":3}}\n\n"
                + "data: [DONE]\n\n";

        AnthropicStreamTranslator translator = createTranslator(true);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        translator.translate(new BufferedReader(new StringReader(sse)), outputStream);
        String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);

        Assertions.assertTrue(output.contains("\"text\":\"Visible text only\""));
        Assertions.assertTrue(output.contains("\"stop_reason\":\"end_turn\""));
    }

    @Test
    void shouldPreserveThinkBlocksWhenFilterDisabled() throws Exception {
        String sse = ""
                + "data: {\"id\":\"chatcmpl-3\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"<think>\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-3\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hidden\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-3\",\"model\":\"MiniMax-M2.1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"</think>Visible text\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":4}}\n\n"
                + "data: [DONE]\n\n";

        AnthropicStreamTranslator translator = createTranslator(false);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        translator.translate(new BufferedReader(new StringReader(sse)), outputStream);
        String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);

        Assertions.assertTrue(output.contains("\"text\":\"<think>\""));
        Assertions.assertTrue(output.contains("\"text\":\"hidden\""));
        Assertions.assertTrue(output.contains("\"text\":\"</think>Visible text\""));
        Assertions.assertTrue(output.contains("\"stop_reason\":\"end_turn\""));
    }

    private AnthropicStreamTranslator createTranslator(boolean filterReasoningText) {
        ProxyProperties properties = new ProxyProperties();
        properties.setFilterReasoningText(filterReasoningText);
        return new AnthropicStreamTranslator(new ObjectMapper(), properties);
    }
}
