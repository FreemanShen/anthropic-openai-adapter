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
 * Assigns a request id and logs inbound request summaries with low overhead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTracingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";

    private static final Logger log = LoggerFactory.getLogger(RequestTracingFilter.class);
    private static final int REQUEST_CACHE_LIMIT = 8192;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        long startNanos = System.nanoTime();
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, REQUEST_CACHE_LIMIT);

        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        log.info("Inbound request method={} uri={} query={} remote={} content_type={} content_length={}",
                request.getMethod(),
                request.getRequestURI(),
                LogSanitizer.normalize(request.getQueryString()),
                request.getRemoteAddr(),
                request.getContentType(),
                request.getContentLengthLong());

        try {
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = response.getStatus();
            String requestBody = LogSanitizer.bodyPreview(
                    wrappedRequest.getContentAsByteArray(), wrappedRequest.getCharacterEncoding());

            if (status >= 500) {
                log.error("Request completed with server error method={} uri={} status={} duration_ms={} request_body={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs, requestBody);
            } else if (status >= 400) {
                log.warn("Request completed with client error method={} uri={} status={} duration_ms={} request_body={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs, requestBody);
            } else {
                log.info("Request completed method={} uri={} status={} duration_ms={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs);
                if (log.isDebugEnabled()) {
                    log.debug("Request payload method={} uri={} body={}",
                            request.getMethod(), request.getRequestURI(), requestBody);
                }
            }
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String headerValue = request.getHeader(REQUEST_ID_HEADER);
        if (headerValue != null && headerValue.trim().length() > 0) {
            return headerValue.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
