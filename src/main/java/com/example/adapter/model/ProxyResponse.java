package com.example.adapter.model;

import org.springframework.http.HttpHeaders;

/**
 * 上游 HTTP 响应的简单值对象（Value Object）。
 *
 * <h2>设计目的</h2>
 * OkHttp 的 Response 类包含大量网络层细节（协议版本、headers 列表等），
 * 本类将 HTTP 代理场景中必需的三个字段提取出来，作为轻量级的返回值。
 *
 * <h2>三个字段</h2>
 * <ul>
 *   <li>{@code statusCode}：HTTP 状态码（如 200、400、502）</li>
 *   <li>{@code headers}：响应头（目前仅使用 Content-Type）</li>
 *   <li>{@code body}：响应体字符串（JSON / SSE 原文）</li>
 * </ul>
 *
 * @see com.example.adapter.service.OpenAiProxyService
 */
public class ProxyResponse {

    private final int statusCode;
    private final HttpHeaders headers;
    private final String body;

    public ProxyResponse(int statusCode, HttpHeaders headers, String body) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }
}
