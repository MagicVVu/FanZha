package com.magicvvu.fanzha.backend.service.fraud.storage;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class FraudNewsRow {
    private String url;
    private String title;
    private String summary;
    private String content;
    private LocalDateTime publishTime;
    private String source;
    private String fraudTags;
    private Double confidence;
    private String rawHtml;
    private String contentHash;
    /** JSON from DeepSeek structured extraction; null if disabled or failed. */
    private String deepseekAnalysisJson;
}
