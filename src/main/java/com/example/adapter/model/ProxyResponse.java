package com.example.adapter.model;

import org.springframework.http.HttpHeaders;

/**
 * 上游响应的简单封装。
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
