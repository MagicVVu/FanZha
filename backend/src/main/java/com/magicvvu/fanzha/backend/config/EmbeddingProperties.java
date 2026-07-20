package com.magicvvu.fanzha.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {
    private String baseUrl = "http://localhost:9001";
    private int timeoutMs = 10000;
    private String model = "bge-large-zh-v1.5";
    private int dim = 1024;
}
