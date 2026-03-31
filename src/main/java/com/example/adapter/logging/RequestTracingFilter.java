package com.example.adapter.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * 请求追踪过滤器：生成/透传请求 ID 并记录入参/出参日志。
 *
 * <h2>核心职责</h2>
 * <ol>
 *   <li><b>请求 ID 管理</b>：优先使用客户端传入的 X-Request-Id，无则自动生成 UUID</li>
 *   <li><b>MDC 注入</b>：将 requestId 放入 MDC，日志自动携带，便于 grep 追踪</li>
 *   <li><b>入参日志</b>：在请求处理前记录 method、uri、content-type、content-length</li>
 *   <li><b>出参日志</b>：在请求处理后记录状态码和耗时，4xx 记录 warn，5xx 记录 error</li>
 * </ol>
 *
 * <h2>请求 ID 透传流程</h2>
 * <pre>
 * 客户端请求头 X-Request-Id: abc123
 *     ↓（本过滤器读取）
 *     ↓ MDC.put("request_id", "abc123")  ← 所有日志自动携带此 ID
 *     ↓ servletResponse.setHeader("X-Request-Id", "abc123")
 *     ↓ OpenAiProxyService 透传给上游
 *     ↓ 上游日志携带此 ID
 * </pre>
 *
 * <h2>日志分级策略</h2>
 * <ul>
 *   <li>2xx：INFO（仅记录完成信息，耗时）</li>
 *   <li>4xx：WARN（客户端错误，可能需要排查请求参数）</li>
 *   <li>5xx：ERROR（服务端错误，需要立即关注）</li>
 * </ul>
 *
 * <h2>ContentCachingRequestWrapper 的作用</h2>
 * Spring 默认的 HttpServletRequest 的 body 只能读取一次。
 * 使用 ContentCachingRequestWrapper 包装后，可以在 filter 链结束后
 * （通过 {@code getContentAsByteArray()}）读取 body 内容用于日志记录，
 * 而不影响 Controller 中再次读取 body。
 *
 * <h2>过滤范围</h2>
 * 仅过滤以 {@code /v1/} 开头的请求，避免过滤健康检查等管理端点。
 *
 * @see RequestTracingFilter#REQUEST_ID_HEADER
 * @see com.example.adapter.service.OpenAiProxyService
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)  // 确保在其他 filter 之前执行，以便所有日志都携带 requestId
public class RequestTracingFilter extends OncePerRequestFilter {

    /** 请求头/响应头/MDC 中的请求 ID 字段名 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC 中的 key（logback pattern 可通过 %X{request_id} 直接引用） */
    public static final String MDC_REQUEST_ID = "request_id";

    private static final Logger log = LoggerFactory.getLogger(RequestTracingFilter.class);

    /**
     * 请求 body 缓存的最大字节数（8KB）。
     * 超过此大小的 body 不会被完整记录，仅记录前 8KB。
     * @see ContentCachingRequestWrapper
     */
    private static final int REQUEST_CACHE_LIMIT = 8192;

    /**
     * 判断是否需要过滤此请求。
     *
     * 仅处理以 /v1/ 开头的 API 请求，跳过静态资源、健康检查等。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 步骤1：解析或生成请求 ID
        String requestId = resolveRequestId(request);
        long startNanos = System.nanoTime();

        // ContentCachingRequestWrapper：在保留原 request 可重复读的同时支持事后读取 body
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, REQUEST_CACHE_LIMIT);

        // 步骤2：将 requestId 注入 MDC（后续所有 log 语句自动携带）
        MDC.put(MDC_REQUEST_ID, requestId);

        // 步骤3：透传给客户端，使客户端能追踪自己的请求
        response.setHeader(REQUEST_ID_HEADER, requestId);

        // 步骤4：记录入参日志
        log.info("Inbound request method={} uri={} query={} remote={} content_type={} content_length={}",
                request.getMethod(),
                request.getRequestURI(),
                LogSanitizer.normalize(request.getQueryString()),
                request.getRemoteAddr(),
                request.getContentType(),
                request.getContentLengthLong());

        try {
            // 步骤5：继续执行 filter 链和 controller
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            // 步骤6：请求处理完毕后（无论成功还是异常），记录出参日志
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = response.getStatus();

            // 从缓存中读取请求 body（已在 filter 链中被读取过，现在可以获取）
            String requestBody = LogSanitizer.bodyPreview(
                    wrappedRequest.getContentAsByteArray(), wrappedRequest.getCharacterEncoding());

            // 根据状态码选择日志级别
            if (status >= 500) {
                log.error("Request completed with server error method={} uri={} status={} duration_ms={} request_body={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs, requestBody);
            } else if (status >= 400) {
                log.warn("Request completed with client error method={} uri={} status={} duration_ms={} request_body={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs, requestBody);
            } else {
                // 成功请求：默认不记录 body（日志量太大），开启 debug 时才记录
                log.info("Request completed method={} uri={} status={} duration_ms={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs);
                if (log.isDebugEnabled()) {
                    log.debug("Request payload method={} uri={} body={}",
                            request.getMethod(), request.getRequestURI(), requestBody);
                }
            }

            // 步骤7：从 MDC 中移除，防止 ThreadLocal 内存泄漏
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    /**
     * 解析请求 ID，优先级：请求头 X-Request-Id > 自动生成 UUID。
     *
     * 若客户端已传入 X-Request-Id，直接透传，保持全链路一致性。
     * 若无，生成随机 UUID（去掉连字符，缩短长度）。
     */
    private String resolveRequestId(HttpServletRequest request) {
        String headerValue = request.getHeader(REQUEST_ID_HEADER);
        if (headerValue != null && headerValue.trim().length() > 0) {
            return headerValue.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
