package com.example.adapter.service;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 负责 Anthropic Messages 接口的请求与响应转换。
 */
@Service
public class AnthropicMessageService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessageService.class);

    private final OpenAiProxyService openAiProxyService;
    private final AnthropicOpenAiMapper mapper;
    private final AnthropicStreamTranslator streamTranslator;

    public AnthropicMessageService(OpenAiProxyService openAiProxyService,
                                   AnthropicOpenAiMapper mapper,
                                   AnthropicStreamTranslator streamTranslator) {
        this.openAiProxyService = openAiProxyService;
        this.mapper = mapper;
        this.streamTranslator = streamTranslator;
    }

    public ResponseEntity<String> handleJson(JsonNode anthropicRequest, HttpHeaders headers) throws IOException {
        JsonNode openAiRequest = mapper.toOpenAiRequest(anthropicRequest);
        log.info("Anthropic 非流式请求已转换为 OpenAI 报文");

        ResponseEntity<String> upstreamResponse = openAiProxyService.forwardOpenAiJson(openAiRequest.toString(), headers);
        JsonNode translatedBody;
        if (upstreamResponse.getStatusCode().is2xxSuccessful()) {
            translatedBody = mapper.toAnthropicResponse(upstreamResponse.getBody());
            log.info("Anthropic 非流式响应转换完成");
        } else {
            translatedBody = mapper.toAnthropicError(upstreamResponse.getBody(), upstreamResponse.getStatusCodeValue());
            log.warn("Anthropic 非流式请求收到上游错误, status={}", upstreamResponse.getStatusCodeValue());
        }

        return ResponseEntity.status(upstreamResponse.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(translatedBody.toString());
    }

    public void handleStream(JsonNode anthropicRequest, HttpHeaders headers, HttpServletResponse servletResponse) throws IOException {
        JsonNode openAiRequest = mapper.toOpenAiRequest(anthropicRequest);
        log.info("Anthropic 流式请求已转换为 OpenAI 流式报文");

        try (Response response = openAiProxyService.executeRaw(openAiRequest.toString(), headers)) {
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("上游未返回流式响应体");
            }

            servletResponse.setStatus(response.code());
            servletResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());

            if (!response.isSuccessful()) {
                String errorBody = body.string();
                log.warn("Anthropic 流式请求失败, status={}, body={}", response.code(), errorBody);
                servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                OutputStream outputStream = servletResponse.getOutputStream();
                outputStream.write(mapper.toAnthropicError(errorBody, response.code()).toString().getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                return;
            }

            servletResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                streamTranslator.translate(reader, servletResponse.getOutputStream());
            }
        }
        log.info("Anthropic 流式响应转换完成");
    }
}
