package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicOpenAiMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AnthropicOpenAiMapper mapper;

    @BeforeEach
    void setUp() {
        ProxyProperties properties = new ProxyProperties();
        properties.setDefaultModel("MiniMax-M2.1");
        mapper = new AnthropicOpenAiMapper(objectMapper, properties);
    }

    @Test
    void shouldConvertAnthropicRequestToOpenAiRequest() throws Exception {
        String anthropicRequest = "{\n" +
                "  \"model\": \"MiniMax-M2.1\",\n" +
                "  \"system\": \"你是一个测试助手\",\n" +
                "  \"max_tokens\": 128,\n" +
                "  \"stop_sequences\": [\"STOP\"],\n" +
                "  \"metadata\": {\"user_id\": \"u-1\"},\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": [\n" +
                "      {\"type\": \"text\", \"text\": \"你好\"},\n" +
                "      {\"type\": \"image\", \"source\": {\"type\": \"url\", \"url\": \"https://example.com/a.png\"}}\n" +
                "    ]},\n" +
                "    {\"role\": \"assistant\", \"content\": [\n" +
                "      {\"type\": \"text\", \"text\": \"我来调用工具\"},\n" +
                "      {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"weather\", \"input\": {\"city\": \"Shanghai\"}}\n" +
                "    ]},\n" +
                "    {\"role\": \"user\", \"content\": [\n" +
                "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_1\", \"content\": [{\"type\": \"text\", \"text\": \"晴天\"}]}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}";

        JsonNode result = mapper.toOpenAiRequest(objectMapper.readTree(anthropicRequest));
        Assertions.assertEquals("MiniMax-M2.1", result.path("model").asText());
        Assertions.assertEquals("STOP", result.path("stop").get(0).asText());
        Assertions.assertEquals("u-1", result.path("user").asText());
        Assertions.assertEquals("system", result.path("messages").get(0).path("role").asText());
        Assertions.assertEquals("你是一个测试助手", result.path("messages").get(0).path("content").asText());
        Assertions.assertEquals("user", result.path("messages").get(1).path("role").asText());
        Assertions.assertEquals("image_url", result.path("messages").get(1).path("content").get(1).path("type").asText());
        Assertions.assertEquals("assistant", result.path("messages").get(2).path("role").asText());
        Assertions.assertEquals("weather", result.path("messages").get(2).path("tool_calls").get(0).path("function").path("name").asText());
        Assertions.assertEquals("tool", result.path("messages").get(3).path("role").asText());
        Assertions.assertEquals("toolu_1", result.path("messages").get(3).path("tool_call_id").asText());
    }

    @Test
    void shouldConvertOpenAiResponseToAnthropicResponse() throws Exception {
        String openAiResponse = "{\n" +
                "  \"id\": \"chatcmpl-123\",\n" +
                "  \"model\": \"MiniMax-M2.1\",\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"finish_reason\": \"tool_calls\",\n" +
                "      \"message\": {\n" +
                "        \"role\": \"assistant\",\n" +
                "        \"content\": \"你好，我先查天气\",\n" +
                "        \"tool_calls\": [\n" +
                "          {\"id\": \"call_1\", \"type\": \"function\", \"function\": {\"name\": \"weather\", \"arguments\": \"{\\\"city\\\":\\\"Shanghai\\\"}\"}}\n" +
                "        ]\n" +
                "      }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"usage\": {\n" +
                "    \"prompt_tokens\": 11,\n" +
                "    \"completion_tokens\": 22\n" +
                "  }\n" +
                "}";

        JsonNode result = mapper.toAnthropicResponse(openAiResponse);
        Assertions.assertEquals("message", result.path("type").asText());
        Assertions.assertEquals("assistant", result.path("role").asText());
        Assertions.assertEquals("text", result.path("content").get(0).path("type").asText());
        Assertions.assertEquals("tool_use", result.path("content").get(1).path("type").asText());
        Assertions.assertEquals("weather", result.path("content").get(1).path("name").asText());
        Assertions.assertEquals("tool_use", result.path("stop_reason").asText());
        Assertions.assertEquals(11, result.path("usage").path("input_tokens").asInt());
        Assertions.assertEquals(22, result.path("usage").path("output_tokens").asInt());
    }

    @Test
    void shouldConvertOpenAiErrorToAnthropicError() {
        JsonNode result = mapper.toAnthropicError("{\"error\":{\"message\":\"bad request\"}}", 400);
        Assertions.assertEquals("error", result.path("type").asText());
        Assertions.assertEquals("invalid_request_error", result.path("error").path("type").asText());
        Assertions.assertEquals("bad request", result.path("error").path("message").asText());
    }

    @Test
    void shouldUseOpenAiCompatibleToolChoiceAndStreamingUsage() throws Exception {
        String anthropicRequest = "{\n" +
                "  \"stream\": true,\n" +
                "  \"tool_choice\": {\"type\": \"auto\", \"disable_parallel_tool_use\": true},\n" +
                "  \"messages\": [{\"role\": \"user\", \"content\": \"hello\"}]\n" +
                "}";

        JsonNode result = mapper.toOpenAiRequest(objectMapper.readTree(anthropicRequest));
        Assertions.assertTrue(result.path("stream").asBoolean());
        Assertions.assertEquals("auto", result.path("tool_choice").asText());
        Assertions.assertTrue(result.path("stream_options").path("include_usage").asBoolean());
        Assertions.assertFalse(result.path("parallel_tool_calls").asBoolean(true));
    }

    @Test
    void shouldConvertRequiredToolChoiceAndNullAssistantContent() throws Exception {
        String anthropicRequest = "{\n" +
                "  \"tool_choice\": {\"type\": \"any\"},\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"assistant\", \"content\": [\n" +
                "      {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"weather\", \"input\": {\"city\": \"Shanghai\"}}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}";

        JsonNode result = mapper.toOpenAiRequest(objectMapper.readTree(anthropicRequest));
        Assertions.assertEquals("required", result.path("tool_choice").asText());
        Assertions.assertTrue(result.path("messages").get(0).path("content").isNull());
        Assertions.assertEquals("weather", result.path("messages").get(0).path("tool_calls").get(0).path("function").path("name").asText());
    }

    @Test
    void shouldStripReasoningAndMapCachedUsage() throws Exception {
        String openAiResponse = "{\n" +
                "  \"id\": \"chatcmpl-456\",\n" +
                "  \"model\": \"MiniMax-M2.1\",\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"finish_reason\": \"content_filter\",\n" +
                "      \"message\": {\n" +
                "        \"role\": \"assistant\",\n" +
                "        \"content\": \"<think>internal</think>Visible answer\"\n" +
                "      }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"usage\": {\n" +
                "    \"prompt_tokens\": 20,\n" +
                "    \"completion_tokens\": 5,\n" +
                "    \"prompt_tokens_details\": {\"cached_tokens\": 7}\n" +
                "  }\n" +
                "}";

        JsonNode result = mapper.toAnthropicResponse(openAiResponse);
        Assertions.assertEquals("Visible answer", result.path("content").get(0).path("text").asText());
        Assertions.assertEquals("refusal", result.path("stop_reason").asText());
        Assertions.assertEquals(13, result.path("usage").path("input_tokens").asInt());
        Assertions.assertEquals(7, result.path("usage").path("cache_read_input_tokens").asInt());
        Assertions.assertEquals(5, result.path("usage").path("output_tokens").asInt());
    }

    @Test
    void shouldPreserveStructuredToolResultPayloads() throws Exception {
        String anthropicRequest = "{\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": [\n" +
                "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_1\", \"is_error\": true, \"content\": [\n" +
                "        {\"type\": \"text\", \"text\": \"upstream failed\"},\n" +
                "        {\"type\": \"image\", \"source\": {\"type\": \"url\", \"url\": \"https://example.com/error.png\"}}\n" +
                "      ]}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}";

        JsonNode result = mapper.toOpenAiRequest(objectMapper.readTree(anthropicRequest));
        String toolContent = result.path("messages").get(0).path("content").asText();

        Assertions.assertEquals("tool", result.path("messages").get(0).path("role").asText());
        Assertions.assertTrue(toolContent.contains("\"is_error\":true"));
        Assertions.assertTrue(toolContent.contains("\"type\":\"image\""));
    }
}
