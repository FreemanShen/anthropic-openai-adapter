package com.example.adapter.config;

import com.example.adapter.logging.UpstreamLoggingInterceptor;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp HTTP 客户端的配置类。
 *
 * <h2>超时设置</h2>
 * <ul>
 *   <li><b>connectTimeout: 30s</b> — 建立 TCP 连接的最大等待时间</li>
 *   <li><b>readTimeout: 0（无限制）</b> — AI 流式响应可能持续很久，不设上限</li>
 *   <li><b>writeTimeout: 30s</b> — 发送请求体的超时（通常请求体较小，30s 足够）</li>
 * </ul>
 *
 * <h2>拦截器链</h2>
 * 目前仅注册了 {@link com.example.adapter.logging.UpstreamLoggingInterceptor}，
 * 负责记录上游调用的请求/响应日志（URL、状态码、耗时）。
 *
 * @see com.example.adapter.logging.UpstreamLoggingInterceptor
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient(UpstreamLoggingInterceptor upstreamLoggingInterceptor) {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                // readTimeout=0 表示不设上限，确保长时间流式响应不会被断开
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(upstreamLoggingInterceptor)
                .build();
    }
}
