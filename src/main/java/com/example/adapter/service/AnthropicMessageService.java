package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import com.example.adapter.logging.BoundedPreviewOutputStream;
import com.example.adapter.logging.LogSanitizer;
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
 * 负责处理 Anthropic Messages API 的请求与响应转换。
 *
 * <h2>核心职责</h2>
 * 这是"翻译层"的核心编排服务：
 * <ol>
 *   <li>接收 Anthropic 格式的 JSON 请求</li>
 *   <li>通过 {@link AnthropicOpenAiMapper} 将请求转换为 OpenAI 格式</li>
 *   <li>通过 {@link OpenAiProxyService} 将请求转发给上游</li>
 *   <li>根据响应类型（非流式/流式）选择不同的翻译路径：
 *     <ul>
 *       <li>非流式：将 OpenAI JSON 响应整体转换为 Anthropic JSON 响应</li>
 *       <li>流式：通过 {@link AnthropicStreamTranslator} 将 SSE 增量事件逐块翻译</li>
 *     </ul>
 *   </li>
 *   <li>错误处理：将上游返回的错误也翻译为 Anthropic 错误格式</li>
 * </ol>
 *
 * <h2>非流式 vs 流式 处理差异</h2>
 * <table>
 *   <tr><th></th><th>非流式（handleJson）</th><th>流式（handleStream）</th></tr>
 *   <tr><td>响应方式</td><td>一次性完整 JSON body</td><td>SSE 逐块推送</td></tr>
 *   <tr><td>翻译器</td><td>{@link AnthropicOpenAiMapper#toAnthropicResponse}</td><td>{@link AnthropicStreamTranslator}</td></tr>
 *   <tr><td>HTTP 方法</td><td>同步 ResponseEntity</td><td>直接写 OutputStream</td></tr>
 * </table>
 *
 * @see AnthropicOpenAiMapper
 * @see AnthropicStreamTranslator
 * @see OpenAiProxyService
 */
@Service
public class AnthropicMessageService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessageService.class);

    private final OpenAiProxyService openAiProxyService;
    private final AnthropicOpenAiMapper mapper;
    private final AnthropicStreamTranslator streamTranslator;
    private final ProxyProperties proxyProperties;

    public AnthropicMessageService(OpenAiProxyService openAiProxyService,
                                   AnthropicOpenAiMapper mapper,
                                   AnthropicStreamTranslator streamTranslator,
                                   ProxyProperties proxyProperties) {
        this.openAiProxyService = openAiProxyService;
        this.mapper = mapper;
        this.streamTranslator = streamTranslator;
        this.proxyProperties = proxyProperties;
    }

    /**
     * 处理非流式 Anthropic 请求。
     *
     * 完整流程：
     * <pre>
     * Anthropic JSON 请求
     *   → toOpenAiRequest（字段映射）
     *   → forwardOpenAiJson（POST 到上游）
     *   → toAnthropicResponse 或 toAnthropicError（响应翻译）
     *   → 返回给客户端
     * </pre>
     *
     * 关键字段映射（Anthropic → OpenAI）包括：
     * model、max_tokens、temperature、top_p、stop_sequences、messages、tools 等。
     * 响应翻译时将 OpenAI 的 finish_reason 映射回 Anthropic 的 stop_reason。
     *
     * @param anthropicRequest 解析后的 Anthropic JSON 节点
     * @param headers           客户端传入的请求头（透传给上游）
     * @return 翻译后的 Anthropic JSON 响应（包装为 ResponseEntity）
     */
    public ResponseEntity<String> handleJson(JsonNode anthropicRequest, HttpHeaders headers) throws IOException {
        // 步骤1：将 Anthropic 格式请求转换为 OpenAI 格式
        JsonNode openAiRequest = mapper.toOpenAiRequest(anthropicRequest);
        log.info("Anthropic 非流式请求已转换为 OpenAI 报文");

        // 步骤2：转发给上游 OpenAI 兼容端点，获取原始响应
        ResponseEntity<String> upstreamResponse = openAiProxyService.forwardOpenAiJson(openAiRequest.toString(), headers);

        // 步骤3：根据上游响应状态决定翻译路径
        JsonNode translatedBody;
        if (upstreamResponse.getStatusCode().is2xxSuccessful()) {
            // 成功响应：将 OpenAI 格式 JSON 转换为 Anthropic 格式 JSON
            translatedBody = mapper.toAnthropicResponse(upstreamResponse.getBody());
            log.info("Anthropic 非流式响应转换完成");
        } else {
            // 错误响应：将 OpenAI 错误结构转换为 Anthropic 错误结构
            translatedBody = mapper.toAnthropicError(upstreamResponse.getBody(), upstreamResponse.getStatusCodeValue());
            log.warn("Anthropic 非流式请求收到上游错误, status={}", upstreamResponse.getStatusCodeValue());
        }

        logAnthropicJsonResponse("Anthropic 非流式响应", translatedBody);

        // 步骤4：构造 HTTP 响应，保持上游状态码，Content-Type 为 JSON
        return ResponseEntity.status(upstreamResponse.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(translatedBody.toString());
    }

    /**
     * 处理流式 Anthropic 请求（Server-Sent Events）。
     *
     * 完整流程：
     * <pre>
     * Anthropic 流式请求
     *   → toOpenAiRequest（字段映射，stream=true）
     *   → executeRaw（获取 OkHttp Response，不关闭 body）
     *   → AnthropicStreamTranslator.translate（逐行解析 SSE，实时翻译）
     *   → 逐事件写入 servlet OutputStream
     * </pre>
     *
     * <h3>关于流式响应不关闭连接</h3>
     * 这里使用 {@code executeRaw} 而非封装方法，是因为流式场景下必须：
     * <ul>
     *   <li>保持 Response body 一直读取直到客户端断开连接或收到 [DONE]</li>
     *   <li>由 {@link AnthropicStreamTranslator#translate} 在读取完毕后自行决定何时关闭流</li>
     *   <li>不在此处调用 {@code response.close()}，否则客户端收到截断的流</li>
     * </ul>
     *
     * <h3>SSE 事件翻译示例</h3>
     * OpenAI 上游 SSE 行：
     * <pre>data: {"choices":[{"delta":{"content":"Hello"}}]}</pre>
     * 翻译为 Anthropic SSE：
     * <pre>
     * event: content_block_start
     * data: {"index":0,"type":"text"}
     * event: content_block_delta
     * data: {"index":0,"type":"text","text":"Hello"}
     * </pre>
     *
     * @param anthropicRequest  解析后的 Anthropic JSON 节点
     * @param headers           客户端请求头（透传给上游）
     * @param servletResponse   直接写入，绕过 Spring MVC 的响应封装
     */
    public void handleStream(JsonNode anthropicRequest, HttpHeaders headers, HttpServletResponse servletResponse) throws IOException {
        // 步骤1：将 Anthropic 请求转换为 OpenAI 格式（stream=true）
        JsonNode openAiRequest = mapper.toOpenAiRequest(anthropicRequest);
        log.info("Anthropic 流式请求已转换为 OpenAI 流式报文");

        // 步骤2：获取上游原始 Response（不自动关闭 body，由 translate 方法控制读取生命周期）
        try (Response response = openAiProxyService.executeRaw(openAiRequest.toString(), headers)) {
            ResponseBody body = response.body();
            if (body == null) {
                // 上游未返回 body 是异常情况，抛出 IOException 触发 GlobalExceptionHandler
                throw new IOException("上游未返回流式响应体");
            }

            // 步骤3：设置 HTTP 响应基础信息
            servletResponse.setStatus(response.code());
            servletResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());

            // 步骤4：处理上游返回错误（HTTP 4xx/5xx），将 OpenAI 错误 JSON 翻译为 Anthropic 格式后返回
            if (!response.isSuccessful()) {
                String errorBody = body.string();
                log.warn("Anthropic 流式请求失败, status={}, body={}", response.code(), errorBody);
                servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                OutputStream outputStream = servletResponse.getOutputStream();
                JsonNode translatedError = mapper.toAnthropicError(errorBody, response.code());
                String translatedErrorBody = translatedError.toString();
                outputStream.write(translatedErrorBody.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                logAnthropicTextResponse("Anthropic 流式错误响应", translatedErrorBody);
                return;
            }

            // 步骤5：设置 SSE 流式响应头
            servletResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");

            // 步骤6：逐行读取上游 SSE，实时翻译为 Anthropic SSE，写入客户端
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                OutputStream outputStream = servletResponse.getOutputStream();
                BoundedPreviewOutputStream previewOutputStream = null;
                OutputStream targetOutputStream = outputStream;
                if (proxyProperties.isLogAnthropicResponseBody()) {
                    previewOutputStream = new BoundedPreviewOutputStream(outputStream, LogSanitizer.MAX_PAYLOAD_LOG_LENGTH);
                    targetOutputStream = previewOutputStream;
                }
                try {
                    streamTranslator.translate(reader, targetOutputStream);
                } finally {
                    if (previewOutputStream != null && previewOutputStream.hasCapturedContent()) {
                        logAnthropicTextResponse("Anthropic 流式响应",
                                previewOutputStream.preview(servletResponse.getCharacterEncoding()));
                    }
                }
            }
        }
        log.info("Anthropic 流式响应转换完成");
    }

    private void logAnthropicJsonResponse(String prefix, JsonNode body) {
        if (body == null) {
            return;
        }
        logAnthropicTextResponse(prefix, body.toString());
    }

    private void logAnthropicTextResponse(String prefix, String body) {
        if (!proxyProperties.isLogAnthropicResponseBody()) {
            return;
        }
        log.info("{} body={}", prefix, LogSanitizer.normalize(LogSanitizer.truncate(body)));
    }
}
