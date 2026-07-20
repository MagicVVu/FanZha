package com.magicvvu.fanzha.backend.service.crawler;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NewsItem {
    private String title;
    private String url;
    private String source;
    private LocalDateTime publishTime;
    private String content; // Raw or cleaned content
    private String summary;
}
