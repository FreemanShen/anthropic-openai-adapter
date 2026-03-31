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
 * Transparent proxy for the upstream OpenAI-compatible API.
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

            servletResponse.setContentType(response.header("Content-Type", "text/event-stream"));
            copy(response.body().byteStream(), servletResponse.getOutputStream());
            log.info("OpenAI stream request forwarding completed");
        }
    }

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

    public Response executeRaw(String requestBody, HttpHeaders incomingHeaders) throws IOException {
        return okHttpClient.newCall(buildRequest(requestBody, incomingHeaders)).execute();
    }

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
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, "application/json");

        if (requestId != null && requestId.trim().length() > 0) {
            builder.header(RequestTracingFilter.REQUEST_ID_HEADER, requestId);
        }
        return builder.build();
    }

    private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, len);
            outputStream.flush();
        }
    }
}
