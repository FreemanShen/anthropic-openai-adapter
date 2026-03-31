package com.example.adapter.logging;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Logs upstream OpenAI-compatible calls without consuming response bodies.
 */
@Component
public class UpstreamLoggingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(UpstreamLoggingInterceptor.class);

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        long startNanos = System.nanoTime();

        log.info("Upstream request method={} url={} content_type={} request_bytes={} has_request_id={}",
                request.method(),
                request.url(),
                request.header("Content-Type"),
                requestBodyLength(request.body()),
                request.header(RequestTracingFilter.REQUEST_ID_HEADER) != null);

        if (log.isDebugEnabled()) {
            log.debug("Upstream request payload method={} url={} body={}",
                    request.method(), request.url(), extractRequestBody(request.body()));
        }

        try {
            Response response = chain.proceed(request);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.info("Upstream response method={} url={} status={} duration_ms={} response_content_type={}",
                    request.method(),
                    request.url(),
                    response.code(),
                    durationMs,
                    response.header("Content-Type"));
            return response;
        } catch (IOException ex) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.error("Upstream call failed method={} url={} duration_ms={}",
                    request.method(), request.url(), durationMs, ex);
            throw ex;
        }
    }

    private long requestBodyLength(RequestBody body) {
        if (body == null) {
            return 0L;
        }
        try {
            return body.contentLength();
        } catch (IOException ex) {
            return -1L;
        }
    }

    private String extractRequestBody(RequestBody body) {
        if (body == null) {
            return "";
        }
        MediaType mediaType = body.contentType();
        if (mediaType == null || !"application".equals(mediaType.type()) || !"json".equals(mediaType.subtype())) {
            return "<non-json-body>";
        }

        Buffer buffer = new Buffer();
        try {
            body.writeTo(buffer);
            return LogSanitizer.truncate(buffer.readUtf8());
        } catch (IOException ex) {
            return "<failed-to-read-body:" + ex.getMessage() + ">";
        }
    }
}
