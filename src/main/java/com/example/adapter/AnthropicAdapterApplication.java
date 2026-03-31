package com.example.adapter;

import com.example.adapter.config.ProxyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProxyProperties.class)
public class AnthropicAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnthropicAdapterApplication.class, args);
    }
}
