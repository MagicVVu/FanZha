package com.magicvvu.fanzha.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "storage.fraud-news")
public class FraudNewsStorageProperties {
    private String mode = "db";
    private String table = "fraud_news";
    private Columns columns = new Columns();

    @Data
    public static class Columns {
        private String url = "url";
        private String title = "title";
        private String summary = "summary";
        private String content = "content";
        private String publishTime = "publish_time";
        private String source = "source";
        private String fraudTags = "fraud_tags";
        private String confidence = "confidence";
        private String rawHtml = "raw_html";
        private String contentHash = "content_hash";
        private String deepseekAnalysis = "deepseek_analysis";
    }
}
