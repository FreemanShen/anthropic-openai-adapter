package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@ExtendWith(OutputCaptureExtension.class)
class AnthropicMessageServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldLogTranslatedAnthropicJsonWhenEnabled(CapturedOutput output) throws Exception {
        OpenAiProxyService openAiProxyService = Mockito.mock(OpenAiProxyService.class);
        AnthropicOpenAiMapper mapper = Mockito.mock(AnthropicOpenAiMapper.class);
        AnthropicStreamTranslator streamTranslator = Mockito.mock(AnthropicStreamTranslator.class);
        ProxyProperties properties = new ProxyProperties();
        properties.setLogAnthropicResponseBody(true);

        AnthropicMessageService service = new AnthropicMessageService(
                openAiProxyService, mapper, streamTranslator, properties);

        JsonNode anthropicRequest = objectMapper.readTree("{\"messages\":[]}");
        JsonNode openAiRequest = objectMapper.readTree("{\"model\":\"glm-4.7-flash\"}");
        JsonNode translatedResponse = objectMapper.readTree("{\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}");

        Mockito.when(mapper.toOpenAiRequest(anthropicRequest)).thenReturn(openAiRequest);
        Mockito.when(openAiProxyService.forwardOpenAiJson(Mockito.eq(openAiRequest.toString()), Mockito.any(HttpHeaders.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"chatcmpl-1\"}"));
        Mockito.when(mapper.toAnthropicResponse("{\"id\":\"chatcmpl-1\"}")).thenReturn(translatedResponse);

        ResponseEntity<String> response = service.handleJson(anthropicRequest, new HttpHeaders());

        Assertions.assertEquals(translatedResponse.toString(), response.getBody());
        Assertions.assertTrue(output.getAll().contains("Anthropic 非流式响应 body="));
        Assertions.assertTrue(output.getAll().contains("\"type\":\"message\""));
    }

    @Test
    void shouldLogTranslatedAnthropicStreamWhenEnabled(CapturedOutput output) throws Exception {
        OpenAiProxyService openAiProxyService = Mockito.mock(OpenAiProxyService.class);
        AnthropicOpenAiMapper mapper = Mockito.mock(AnthropicOpenAiMapper.class);
        AnthropicStreamTranslator streamTranslator = Mockito.mock(AnthropicStreamTranslator.class);
        ProxyProperties properties = new ProxyProperties();
        properties.setLogAnthropicResponseBody(true);

        AnthropicMessageService service = new AnthropicMessageService(
                openAiProxyService, mapper, streamTranslator, properties);

        JsonNode anthropicRequest = objectMapper.readTree("{\"messages\":[],\"stream\":true}");
        JsonNode openAiRequest = objectMapper.readTree("{\"stream\":true}");
        Mockito.when(mapper.toOpenAiRequest(anthropicRequest)).thenReturn(openAiRequest);
        Mockito.when(openAiProxyService.executeRaw(Mockito.eq(openAiRequest.toString()), Mockito.any(HttpHeaders.class)))
                .thenReturn(successfulStreamResponse());
        Mockito.doAnswer(invocation -> {
            OutputStream outputStream = invocation.getArgument(1);
            outputStream.write(("event: message_start\n"
                    + "data: {\"type\":\"message_start\"}\n\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            return null;
        }).when(streamTranslator).translate(Mockito.any(BufferedReader.class), Mockito.any(OutputStream.class));

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        service.handleStream(anthropicRequest, new HttpHeaders(), servletResponse);

        Assertions.assertTrue(output.getAll().contains("Anthropic 流式响应 body="));
        Assertions.assertTrue(output.getAll().contains("event: message_start"));
    }

    private Response successfulStreamResponse() {
        Request request = new Request.Builder()
                .url("http://localhost/v1/chat/completions")
                .build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(
                        okhttp3.MediaType.get("text/event-stream; charset=utf-8"),
                        "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)))
                .build();
    }
}
