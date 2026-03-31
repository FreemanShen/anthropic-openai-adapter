package com.example.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上游 OpenAI 兼容端点的连接配置。
 *
 * <h2>配置项（application.yml）</h2>
 * <pre>
 * adapter:
 *   upstream:
 *     base-url: https://api.minimaxi.com/v1          # 上游 API 基础地址
 *     chat-completions-path: /chat/completions       # Chat Completions 端点路径
 *     api-key: sk-xxx                                 # 上游 API Key（兜底）
 *     default-model: MiniMax-M2.1                     # 未指定模型时的默认值
 * </pre>
 *
 * <h2>各字段说明</h2>
 * <ul>
 *   <li>{@code baseUrl}：上游服务商的基础 URL，包含协议和版本路径前缀</li>
 *   <li>{@code chatCompletionsPath}：Chat Completions API 的路径（会拼接到 baseUrl 后）</li>
 *   <li>{@code apiKey}：上游 API Key。若客户端请求中未提供 Authorization，则使用此值作为兜底</li>
 *   <li>{@code defaultModel}：当 Anthropic 请求未指定 model 时使用的默认值</li>
 * </ul>
 *
 * <h2>为什么要拆分 baseUrl 和 path？</h2>
 * 这样设计便于在不同部署环境下灵活配置：
 * - 开发环境：使用本地 Mock 服务器
 * - 测试/生产：指向 MiniMax / OpenAI / 其他兼容端点
 *
 * @see com.example.adapter.service.OpenAiProxyService
 */
@ConfigurationProperties(prefix = "adapter.upstream")
public class ProxyProperties {

    /** 上游 API 基础地址（不含路径），默认 MiniMax */
    private String baseUrl = "https://api.minimaxi.com/v1";

    /** Chat Completions API 路径（拼接在 baseUrl 后） */
    private String chatCompletionsPath = "/chat/completions";

    /** 上游 API Key（兜底值，优先级最低） */
    private String apiKey;

    /** 未指定模型时的默认模型名 */
    private String defaultModel = "MiniMax-M2.1";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChatCompletionsPath() {
        return chatCompletionsPath;
    }

    public void setChatCompletionsPath(String chatCompletionsPath) {
        this.chatCompletionsPath = chatCompletionsPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }
}
