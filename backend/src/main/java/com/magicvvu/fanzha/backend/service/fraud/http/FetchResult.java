package com.magicvvu.fanzha.backend.service.fraud.http;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FetchResult {
    private String url;
    private int status;
    private String finalUrl;
    private String contentType;
    private String body;
}
