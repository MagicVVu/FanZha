package com.magicvvu.fanzha.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "deepseek-extract")
public class FraudPipelineProperties {
    private boolean enabled = false;
    private String model;
    private int timeoutMs = 60000;
    private int maxRetries = 5;
}
