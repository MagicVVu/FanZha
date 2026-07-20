package com.magicvvu.fanzha.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "vector-store")
public class VectorStoreProperties {
    private String type = "milvus";
    private MilvusProperties milvus = new MilvusProperties();

    @Data
    public static class MilvusProperties {
        private String host = "localhost";
        private int port = 19530;
        private String collection = "fraud_cases";
        private int dim = 1024;
        private int nlist = 2048;
    }
}
