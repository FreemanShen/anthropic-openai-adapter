package com.example.adapter.controller;

import com.example.adapter.service.AnthropicMessageService;
import com.example.adapter.service.OpenAiProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 对外暴露 OpenAI 与 Anthropic 两套入口。
 */
@RestController
public class ApiProxyController {

    private static final Logger log = LoggerFactory.getLogger(ApiProxyController.class);

    private final ObjectMapper objectMapper;
    private final OpenAiProxyService openAiProxyService;
    private final AnthropicMessageService anthropicMessageService;

    public ApiProxyController(ObjectMapper objectMapper,
                              OpenAiProxyService openAiProxyService,
                              AnthropicMessageService anthropicMessageService) {
        this.objectMapper = objectMapper;
        this.openAiProxyService = openAiProxyService;
        this.anthropicMessageService = anthropicMessageService;
    }

    @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void proxyOpenAi(@RequestBody String requestBody,
                            @RequestHeader HttpHeaders headers,
                            HttpServletResponse response) throws IOException {
        JsonNode requestJson = objectMapper.readTree(requestBody);
        boolean stream = requestJson.path("stream").asBoolean(false);
        log.info("收到 OpenAI 兼容请求, stream={}", stream);

        if (stream) {
            openAiProxyService.forwardOpenAiStream(requestBody, headers, response);
            return;
        }

        ResponseEntity<String> upstreamResponse = openAiProxyService.forwardOpenAiJson(requestBody, headers);
        writeJsonResponse(response, upstreamResponse);
    }

    @PostMapping(value = "/v1/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void proxyAnthropic(@RequestBody String requestBody,
                               @RequestHeader HttpHeaders headers,
                               HttpServletResponse response) throws IOException {
        JsonNode requestJson = objectMapper.readTree(requestBody);
        boolean stream = requestJson.path("stream").asBoolean(false);
        log.info("收到 Anthropic 请求, stream={}", stream);

        if (stream) {
            anthropicMessageService.handleStream(requestJson, headers, response);
            return;
        }

        ResponseEntity<String> translatedResponse = anthropicMessageService.handleJson(requestJson, headers);
        writeJsonResponse(response, translatedResponse);
    }

    private void writeJsonResponse(HttpServletResponse response, ResponseEntity<String> entity) throws IOException {
        response.setStatus(entity.getStatusCodeValue());
        String contentType = entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        response.setContentType(contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (entity.getBody() != null) {
            response.getWriter().write(entity.getBody());
            response.getWriter().flush();
        }
    }
}
