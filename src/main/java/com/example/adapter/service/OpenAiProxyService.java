package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import com.example.adapter.logging.LogSanitizer;
import com.example.adapter.logging.RequestTracingFilter;
import com.example.adapter.model.ProxyResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 向上游 OpenAI 兼容端点（默认 MiniMax）转发 HTTP 请求的客户端封装。
 *
 * <h2>职责范围</h2>
 * 本服务仅负责网络层操作，不涉及任何协议转换：
 * <ul>
 *   <li>构建 OkHttp Request（URL、Headers、Body）</li>
 *   <li>执行 HTTP 请求（同步/流式）</li>
 *   <li>返回响应（JSON 封装或原始 Response）</li>
 * </ul>
 *
 * <h2>三个入口方法的区别</h2>
 * <table>
 *   <tr><th>方法</th><th>返回值</th><th>适用场景</th><th>Body 生命周期</th></tr>
 *   <tr><td>{@link #forwardOpenAiJson}</td><td>ResponseEntity&lt;String&gt;</td><td>Anthropic 非流式响应</td><td>自动读取并关闭</td></tr>
 *   <tr><td>{@link #forwardOpenAiStream}</td><td>void（直接写 servlet）</td><td>OpenAI 流式透传</td><td>边读边写，手动 flush</td></tr>
 *   <tr><td>{@link #executeRaw}</td><td>okhttp3.Response</td><td>Anthropic 流式转换</td><td>调用方控制读取和关闭</td></tr>
 *   <tr><td>{@link #executeJson}</td><td>ProxyResponse</td><td>通用 JSON 请求</td><td>自动读取并关闭</td></tr>
 * </table>
 *
 * <h2>关于请求 ID 透传</h2>
 * 通过 {@link RequestTracingFilter} 在请求进入时生成或读取 X-Request-Id，
 * 并通过 MDC 记录日志。本服务将其透传到上游请求头，以便全链路追踪。
 *
 * @see HeaderResolver
 * @see ProxyProperties
 */
@Service
public class OpenAiProxyService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProxyService.class);

    private final OkHttpClient okHttpClient;
    private final ProxyProperties proxyProperties;
    private final HeaderResolver headerResolver;

    public OpenAiProxyService(OkHttpClient okHttpClient,
                              ProxyProperties proxyProperties,
                              HeaderResolver headerResolver) {
        this.okHttpClient = okHttpClient;
        this.proxyProperties = proxyProperties;
        this.headerResolver = headerResolver;
    }

    /**
     * 转发 OpenAI JSON 请求（非流式），返回完整响应。
     *
     * 用于 Anthropic 非流式请求的处理链：
     * Anthropic 请求 → AnthropicMessageService.handleJson → 本方法 → 上游响应
     *
     * 特点：
     * - 返回完整的响应 body（String），由调用方负责转换
     * - ResponseEntity 封装了状态码和 headers
     *
     * @param requestBody      JSON 请求体（已转换好的 OpenAI 格式）
     * @param incomingHeaders  客户端传入的 headers（用于提取 Authorization 和透传 X-Request-Id）
     * @return 包含状态码、响应头和 body 的 ResponseEntity
     */
    public ResponseEntity<String> forwardOpenAiJson(String requestBody, HttpHeaders incomingHeaders) throws IOException {
        log.info("Forwarding OpenAI JSON request upstream");
        if (log.isDebugEnabled()) {
            log.debug("OpenAI JSON request payload={}", LogSanitizer.truncate(requestBody));
        }

        ProxyResponse proxyResponse = executeJson(requestBody, incomingHeaders);
        log.info("OpenAI JSON request completed, status={}", proxyResponse.getStatusCode());
        return ResponseEntity.status(proxyResponse.getStatusCode())
                .headers(proxyResponse.getHeaders())
                .body(proxyResponse.getBody());
    }

    /**
     * 转发 OpenAI 流式请求（SSE），直接透传到客户端。
     *
     * 用于 OpenAI 兼容客户端的流式请求：
     * 客户端 → /v1/chat/completions → 本方法 → 上游 SSE → 客户端
     *
     * 特点：
     * - 不做任何协议转换，上游 SSE 直接透传
     * - 通过逐字节复制（{@link #copy}）保持流式特性
     * - 错误响应（非 2xx）时返回 JSON 错误体（而非 SSE）
     *
     * @param requestBody      JSON 请求体
     * @param incomingHeaders  请求头
     * @param servletResponse  直接写入客户端 response
     */
    public void forwardOpenAiStream(String requestBody,
                                    HttpHeaders incomingHeaders,
                                    HttpServletResponse servletResponse) throws IOException {
        Request request = buildRequest(requestBody, incomingHeaders);
        log.info("Forwarding OpenAI stream request upstream");

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.body() == null) {
                throw new IOException("Upstream returned an empty response body");
            }

            servletResponse.setStatus(response.code());
            servletResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());

            // 处理 HTTP 错误（4xx/5xx），转为 JSON 错误响应
            if (!response.isSuccessful()) {
                String errorBody = response.body().string();
                log.warn("OpenAI stream request failed, status={}, body={}",
                        response.code(), LogSanitizer.truncate(errorBody));
                servletResponse.setContentType("application/json");
                OutputStream outputStream = servletResponse.getOutputStream();
                outputStream.write(errorBody.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                return;
            }

            // 成功时：透传 Content-Type 和 SSE 流
            servletResponse.setContentType(response.header("Content-Type", "text/event-stream"));
            copy(response.body().byteStream(), servletResponse.getOutputStream());
            log.info("OpenAI stream request forwarding completed");
        }
    }

    /**
     * 执行 JSON 请求，返回原始 OkHttp Response（供调用方控制 body 生命周期）。
     *
     * 与 {@link #forwardOpenAiJson} 的关键区别：
     * - 返回原始 okhttp3.Response，不自动读取 body
     * - 调用方负责读取 body 并在适当时机关闭 response
     *
     * 用途：Anthropic 流式请求需要保持 Response 打开，逐行读取 SSE：
     * <pre>
     * try (Response response = executeRaw(...)) {
     *     BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()));
     *     streamTranslator.translate(reader, outputStream);
     * } // 流读取完毕后，response 在此自动关闭
     * </pre>
     *
     * @param requestBody     JSON 请求体
     * @param incomingHeaders  请求头
     * @return OkHttp Response（由调用方负责关闭）
     */
    public Response executeRaw(String requestBody, HttpHeaders incomingHeaders) throws IOException {
        return okHttpClient.newCall(buildRequest(requestBody, incomingHeaders)).execute();
    }

    /**
     * 执行 JSON 请求，返回封装后的 ProxyResponse。
     *
     * @param requestBody     JSON 请求体
     * @param incomingHeaders 请求头
     * @return 包含状态码、响应头、body 的 ProxyResponse
     */
    public ProxyResponse executeJson(String requestBody, HttpHeaders incomingHeaders) throws IOException {
        Request request = buildRequest(requestBody, incomingHeaders);
        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String payload = body == null ? "" : body.string();
            HttpHeaders headers = new HttpHeaders();
            if (response.header("Content-Type") != null) {
                headers.set(HttpHeaders.CONTENT_TYPE, response.header("Content-Type"));
            }
            return new ProxyResponse(response.code(), headers, payload);
        }
    }

    /**
     * 构建发往上游的 OkHttp Request。
     *
     * 构建步骤：
     * <ol>
     *   <li>从 {@link HeaderResolver} 获取 API Key（支持多种来源）</li>
     *   <li>拼接上游 URL：baseUrl + chatCompletionsPath</li>
     *   <li>从 MDC 获取当前请求 ID（用于链路追踪）</li>
     *   <li>设置请求头：Authorization（Bearer Token）、Content-Type、X-Request-Id</li>
     * </ol>
     *
     * <h3>API Key 优先级</h3>
     * 1. 请求头 Authorization: Bearer xxx（优先）
     * 2. 请求头 x-api-key
     * 3. 配置文件中的 adapter.upstream.api-key（兜底）
     *
     * <h3>X-Request-Id 透传规则</h3>
     * 先从请求头中查找 X-Request-Id，若无则从 MDC（{@link RequestTracingFilter} 设置）中获取，
     * 若均无则不传递（上游将自行生成）。
     */
    private Request buildRequest(String requestBody, HttpHeaders incomingHeaders) {
        String apiKey = headerResolver.resolveApiKey(incomingHeaders);
        String url = proxyProperties.getBaseUrl() + proxyProperties.getChatCompletionsPath();
        String requestId = incomingHeaders.getFirst(RequestTracingFilter.REQUEST_ID_HEADER);
        if (requestId == null || requestId.trim().length() == 0) {
            requestId = MDC.get(RequestTracingFilter.MDC_REQUEST_ID);
        }

        log.info("Preparing upstream OpenAI-compatible call url={}", url);

        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, MediaType.get("application/json; charset=utf-8")))
                // Authorization: Bearer <api-key>（OpenAI/MiniMax 标准认证方式）
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, "application/json");

        // 透传 X-Request-Id，供上游记录到日志，实现全链路追踪
        if (requestId != null && requestId.trim().length() > 0) {
            builder.header(RequestTracingFilter.REQUEST_ID_HEADER, requestId);
        }
        return builder.build();
    }

    /**
     * 将输入流逐块复制到输出流（用于 SSE 透传）。
     *
     * 注意：每次写入后都调用 flush()，确保客户端能实时收到 SSE 事件。
     * 这是 SSE 流式传输的关键——不能使用缓冲后一次性发送。
     *
     * @param inputStream  上游 SSE 输入流
     * @param outputStream  客户端响应输出流
     */
    private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, len);
            outputStream.flush(); // SSE 必须实时 flush
        }
    }
}
