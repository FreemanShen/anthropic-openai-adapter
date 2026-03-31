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
 * HTTP 请求入口，同时支持 Anthropic Messages API 和 OpenAI Chat Completions API。
 *
 * <h2>设计背景</h2>
 * 客户端通常只集成一种 API（Anthropic 或 OpenAI），而实际部署的上游服务商可能不同。
 * 本适配器充当"翻译层"：
 * <ul>
 *   <li>客户端 → Anthropic 风格请求 → 本控制器 → 转换为 OpenAI 格式 → 上游 MiniMax</li>
 *   <li>上游 MiniMax → OpenAI 格式响应 → 本控制器 → 翻译回 Anthropic 格式 → 客户端</li>
 * </ul>
 *
 * <h2>端点路由</h2>
 * <ul>
 *   <li>{@code POST /v1/messages}          → Anthropic Messages 风格（同步/流式），需协议转换</li>
 *   <li>{@code POST /v1/chat/completions}  → OpenAI Chat Completions 风格（同步/流式），直接透传</li>
 * </ul>
 *
 * <h2>关于 SSE 流式响应</h2>
 * Anthropic 流式响应使用 Server-Sent Events（SSE），每条事件有 "event" 字段标识类型。
 * 关键事件依次：message_start → content_block_start → content_block_delta → ... → message_delta → message_stop。
 * 具体翻译逻辑见 {@link com.example.adapter.service.AnthropicStreamTranslator}。
 *
 * @see AnthropicMessageService
 * @see OpenAiProxyService
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

    /**
     * OpenAI Chat Completions 入口（透传模式）。
     *
     * 流程：客户端 OpenAI 请求 → 直接转发给上游 → 上游响应原样返回。
     * 不做任何协议转换，因为请求和响应格式与上游一致。
     *
     * @param requestBody JSON 请求体（OpenAI 格式）
     * @param headers     透传必要头部（Authorization 等）
     * @param response    直接写入 servlet response，避免框架额外处理
     */
    @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void proxyOpenAi(@RequestBody String requestBody,
                            @RequestHeader HttpHeaders headers,
                            HttpServletResponse response) throws IOException {
        JsonNode requestJson = objectMapper.readTree(requestBody);
        boolean stream = requestJson.path("stream").asBoolean(false);
        log.info("收到 OpenAI 兼容请求, stream={}", stream);

        if (stream) {
            // 流式：直接透传 SSE 流到客户端，不做协议转换
            openAiProxyService.forwardOpenAiStream(requestBody, headers, response);
            return;
        }

        // 非流式：透传 JSON 响应
        ResponseEntity<String> upstreamResponse = openAiProxyService.forwardOpenAiJson(requestBody, headers);
        writeJsonResponse(response, upstreamResponse);
    }

    /**
     * Anthropic Messages 入口（协议转换模式）。
     *
     * 流程：
     * <ol>
     *   <li>解析请求体，判断是流式还是非流式</li>
     *   <li>将 Anthropic 格式请求转换为 OpenAI 格式，转发给上游</li>
     *   <li>将上游 OpenAI 格式响应转换回 Anthropic 格式返回</li>
     * </ol>
     *
     * 流式与非流式的区别：
     * <ul>
     *   <li>非流式：一次性 JSON 响应，转换逻辑在 {@link com.example.adapter.service.AnthropicOpenAiMapper}</li>
     *   <li>流式：SSE 增量事件，逐块翻译，逻辑在 {@link com.example.adapter.service.AnthropicStreamTranslator}</li>
     * </ul>
     *
     * @param requestBody JSON 请求体（Anthropic 格式）
     * @param headers     透传必要头部（Authorization 等）
     * @param response    写入翻译后的响应
     */
    @PostMapping(value = "/v1/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void proxyAnthropic(@RequestBody String requestBody,
                               @RequestHeader HttpHeaders headers,
                               HttpServletResponse response) throws IOException {
        JsonNode requestJson = objectMapper.readTree(requestBody);
        boolean stream = requestJson.path("stream").asBoolean(false);
        log.info("收到 Anthropic 请求, stream={}", stream);

        if (stream) {
            // 流式：通过 streamTranslator 实时翻译 SSE 事件
            anthropicMessageService.handleStream(requestJson, headers, response);
            return;
        }

        // 非流式：一次性转换完整响应
        ResponseEntity<String> translatedResponse = anthropicMessageService.handleJson(requestJson, headers);
        writeJsonResponse(response, translatedResponse);
    }

    /**
     * 将 ResponseEntity 中的状态码、Content-Type 和 body 写入 HttpServletResponse。
     *
     * @param response 目标 servlet response
     * @param entity   待写入的实体（来自 service 层）
     */
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
