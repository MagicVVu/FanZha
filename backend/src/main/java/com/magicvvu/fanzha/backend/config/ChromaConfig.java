package com.magicvvu.fanzha.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "chroma")
public class ChromaConfig {
    private String host = "localhost";
    private int port = 8000;
    private String tenant = "default_tenant";
    private String database = "default_database";
    private String collectionName = "anti_fraud_news";
    private boolean autoInit = true;
}
