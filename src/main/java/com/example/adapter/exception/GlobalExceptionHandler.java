package com.example.adapter.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

/**
 * 全局异常处理器，将各类异常统一转换为 API 错误响应格式。
 *
 * <h2>设计背景</h2>
 * 本适配器作为 HTTP 代理层，可能遇到两类错误：
 * <ul>
 *   <li><b>本地异常</b>：请求解析失败、缺少 API Key、JSON 格式错误等</li>
 *   <li><b>上游异常</b>：由 {@link com.example.adapter.service.OpenAiProxyService} 抛出
 *       （如网络超时、连接失败）</li>
 * </ul>
 *
 * 上游异常通常已经在 OpenAiProxyService 或 UpstreamLoggingInterceptor 中记录日志，
 * 本处理器负责将其转换为符合客户端期望的错误格式返回。
 *
 * <h2>错误格式区分</h2>
 * 响应格式根据请求路径自动选择：
 * <ul>
 *   <li>{@code /v1/messages} → Anthropic 错误格式：<pre>{"type":"error","error":{"type":"...","message":"..."}}</pre></li>
 *   <li>其他路径 → OpenAI 错误格式：<pre>{"error":{"type":"...","message":"..."}}</pre></li>
 * </ul>
 *
 * <h2>HTTP 状态码映射</h2>
 * <table>
 *   <tr><th>异常类型</th><th>HTTP 状态码</th><th>含义</th></tr>
 *   <tr><td>IllegalArgumentException</td><td>400 Bad Request</td><td>请求参数校验失败（如缺少 API Key）</td></tr>
 *   <tr><td>JsonProcessingException</td><td>400 Bad Request</td><td>JSON 解析失败</td></tr>
 *   <tr><td>HttpMessageNotReadableException</td><td>400 Bad Request</td><td>请求体无法读取/解析</td></tr>
 *   <tr><td>其他未分类异常</td><td>502 Bad Gateway</td><td>上游调用失败或其他内部错误</td></tr>
 * </table>
 *
 * @see com.example.adapter.service.AnthropicOpenAiMapper#toAnthropicError
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 处理客户端请求错误（400 Bad Request）。
     *
     * 涵盖的异常：
     * <ul>
     *   <li>{@code IllegalArgumentException}：缺少 API Key、参数校验失败等</li>
     *   <li>{@code JsonProcessingException}：请求体 JSON 格式错误</li>
     *   <li>{@code HttpMessageNotReadableException}：Spring 无法解析请求体</li>
     * </ul>
     */
    @ExceptionHandler({
            IllegalArgumentException.class,
            JsonProcessingException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<String> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.warn("Request validation failed, uri={}", request.getRequestURI(), ex);
        return buildErrorResponse(request, HttpStatus.BAD_REQUEST, "invalid_request_error", ex.getMessage());
    }

    /**
     * 处理所有未分类异常（502 Bad Gateway）。
     *
     * 包括：
     * <ul>
     *   <li>上游连接失败（IOException）</li>
     *   <li>上游返回非 JSON 响应</li>
     *   <li>其他未预期的运行时异常</li>
     * </ul>
     *
     * 注意：502 表示"上游出了问题"，而非客户端问题。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex, HttpServletRequest request) {
        log.error("Request processing failed, uri={}", request.getRequestURI(), ex);
        return buildErrorResponse(request, HttpStatus.BAD_GATEWAY, "api_error", ex.getMessage());
    }

    /**
     * 根据请求路径选择 Anthropic 或 OpenAI 错误格式。
     */
    private ResponseEntity<String> buildErrorResponse(HttpServletRequest request,
                                                      HttpStatus status,
                                                      String errorType,
                                                      String message) {
        ObjectNode payload = isAnthropicRequest(request)
                ? buildAnthropicError(errorType, message)
                : buildOpenAiError(errorType, message);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload.toString());
    }

    /**
     * 判断是否为 Anthropic Messages API 请求。
     */
    private boolean isAnthropicRequest(HttpServletRequest request) {
        return request != null
                && request.getRequestURI() != null
                && request.getRequestURI().startsWith("/v1/messages");
    }

    /**
     * 构建 Anthropic 错误格式：
     * <pre>{"type":"error","error":{"type":"...","message":"..."}}</pre>
     */
    private ObjectNode buildAnthropicError(String errorType, String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "error");

        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", errorType);
        error.put("message", message);
        payload.set("error", error);
        return payload;
    }

    /**
     * 构建 OpenAI 错误格式：
     * <pre>{"error":{"type":"...","message":"..."}}</pre>
     */
    private ObjectNode buildOpenAiError(String errorType, String message) {
        ObjectNode payload = objectMapper.createObjectNode();

        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", errorType);
        error.put("message", message);
        payload.set("error", error);
        return payload;
    }
}
