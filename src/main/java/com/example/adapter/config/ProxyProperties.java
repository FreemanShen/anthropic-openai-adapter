package com.example.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 适配器的上游配置。
 */
@ConfigurationProperties(prefix = "adapter.upstream")
public class ProxyProperties {

    private String baseUrl = "https://api.minimaxi.com/v1";
    private String chatCompletionsPath = "/chat/completions";
    private String apiKey;
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
