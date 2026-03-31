package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 统一解析客户端请求头中的鉴权信息。
 */
@Component
public class HeaderResolver {

    private final ProxyProperties proxyProperties;

    public HeaderResolver(ProxyProperties proxyProperties) {
        this.proxyProperties = proxyProperties;
    }

    public String resolveApiKey(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }

        String xApiKey = headers.getFirst("x-api-key");
        if (StringUtils.hasText(xApiKey)) {
            return xApiKey.trim();
        }

        if (StringUtils.hasText(proxyProperties.getApiKey())) {
            return proxyProperties.getApiKey().trim();
        }

        throw new IllegalArgumentException("未提供可用的 API Key，请通过 Authorization、x-api-key 或配置项传入");
    }
}
