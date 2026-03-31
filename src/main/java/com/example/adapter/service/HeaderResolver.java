package com.example.adapter.service;

import com.example.adapter.config.ProxyProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 统一解析客户端请求头中的 API 鉴权信息（API Key）。
 *
 * <h2>设计背景</h2>
 * 不同客户端和不同场景下，API Key 的传递方式可能不同：
 * <ul>
 *   <li>标准方式：Authorization: Bearer &lt;key&gt;</li>
 *   <li>备用方式：x-api-key: &lt;key&gt;</li>
 *   <li>服务端配置兜底：application.yml 中的 adapter.upstream.api-key</li>
 * </ul>
 *
 * 本组件对调用方屏蔽这些差异，提供统一的 API Key 获取接口。
 *
 * <h2>优先级（从高到低）</h2>
 * <ol>
 *   <li>Authorization: Bearer &lt;key&gt;（标准 OAuth2 / OpenAI 方式）</li>
 *   <li>x-api-key: &lt;key&gt;（某些客户端框架使用此头部）</li>
 *   <li>配置文件 adapter.upstream.api-key（服务端统一配置，无客户端传入时使用）</li>
 * </ol>
 *
 * <h2>异常处理</h2>
 * 若以上三者均无有效值，抛出 IllegalArgumentException。
 * 此异常会被 {@link com.example.adapter.exception.GlobalExceptionHandler} 捕获，
 * 翻译为 400 Bad Request 的 Anthropic/OpenAI 错误格式返回。
 *
 * @see ProxyProperties
 */
@Component
public class HeaderResolver {

    private final ProxyProperties proxyProperties;

    public HeaderResolver(ProxyProperties proxyProperties) {
        this.proxyProperties = proxyProperties;
    }

    /**
     * 从请求头中解析 API Key，支持多种来源。
     *
     * <h3>典型 HTTP 请求示例</h3>
     * <pre>
     * POST /v1/messages HTTP/1.1
     * Host: adapter.example.com
     * Authorization: Bearer sk-ant-xxxxx       ← 优先使用
     * Content-Type: application/json
     * </pre>
     *
     * <h3>调用场景</h3>
     * 本方法在 {@link OpenAiProxyService#buildRequest} 中被调用，
     * 用于构建发往上游的请求。API Key 会被设置为：
     * <pre>Authorization: Bearer &lt;resolved-api-key&gt;</pre>
     *
     * @param headers 客户端传入的 HTTP 请求头
     * @return 解析出的 API Key（不包含 "Bearer " 前缀）
     * @throws IllegalArgumentException 当没有任何可用的 API Key 时抛出
     */
    public String resolveApiKey(HttpHeaders headers) {
        // 优先级1：标准 Authorization: Bearer 头部
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }

        // 优先级2：x-api-key 头部（某些 SDK/客户端使用）
        String xApiKey = headers.getFirst("x-api-key");
        if (StringUtils.hasText(xApiKey)) {
            return xApiKey.trim();
        }

        // 优先级3：配置文件中的服务端 API Key（作为全局兜底）
        if (StringUtils.hasText(proxyProperties.getApiKey())) {
            return proxyProperties.getApiKey().trim();
        }

        // 无任何可用 API Key → 拒绝请求
        throw new IllegalArgumentException("未提供可用的 API Key，请通过 Authorization、x-api-key 或配置项传入");
    }
}
