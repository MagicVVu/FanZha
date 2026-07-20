package com.magicvvu.fanzha.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(DeepSeekConfig.DeepSeekProperties.class)
public class DeepSeekConfig {
    @Bean
    public RestTemplate deepSeekRestTemplate(RestTemplateBuilder builder, DeepSeekProperties properties) {
        return builder
                .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();
    }

    @Data
    @ConfigurationProperties(prefix = "deepseek")
    public static class DeepSeekProperties {
        private String baseUrl;
        private String apiKey;
        private String model;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 60000;
        private String embeddingModel = "deepseek-embedding"; // Default embedding model
    }
}
