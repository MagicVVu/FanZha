package com.magicvvu.fanzha.backend.service.fraud.checkpoint;

import lombok.Data;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Data
public class CrawlerCheckpoint {
    private long lastRunEpochMs = 0L;
    private Set<String> seenUrls = new LinkedHashSet<>();
    private Set<String> seenContentHashes = new LinkedHashSet<>();
    private Set<String> seenTitleFingerprints = new LinkedHashSet<>();
    private Map<String, Long> sourceLastSuccessEpochMs = new HashMap<>();
    private Map<String, Integer> sourceConsecutiveFailures = new HashMap<>();
    private Map<String, Long> sourceLastAlertEpochMs = new HashMap<>();
}
