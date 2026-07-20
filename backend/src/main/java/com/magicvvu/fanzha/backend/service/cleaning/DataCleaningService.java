package com.magicvvu.fanzha.backend.service.cleaning;

import com.hankcs.hanlp.HanLP;
import com.magicvvu.fanzha.backend.service.crawler.NewsItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataCleaningService {

    private static final Set<String> BLACKLIST;
    static {
        Set<String> set = new HashSet<>();
        set.add("广告");
        set.add("推广");
        set.add("彩票");
        set.add("赌博");
        BLACKLIST = java.util.Collections.unmodifiableSet(set);
    }
    private final Set<String> seenUrls = new HashSet<>(); // In-memory dedupe (should be Redis/DB)

    public List<NewsItem> clean(List<NewsItem> rawItems) {
        List<NewsItem> cleaned = new ArrayList<>();
        int duplicate = 0;
        int tooShort = 0;
        int blacklisted = 0;

        for (NewsItem item : rawItems) {
            // 1. Deduplication (URL MD5)
            String urlHash = md5(item.getUrl());
            if (seenUrls.contains(urlHash)) {
                log.debug("Duplicate URL skipped: {}", item.getUrl());
                duplicate++;
                continue;
            }
            seenUrls.add(urlHash);

            // 2. Length Filter
            if (item.getContent() == null || item.getContent().length() < 80) {
                log.debug("Content too short skipped: {}", item.getTitle());
                tooShort++;
                continue;
            }

            // 3. Sensitive Word Filter
            if (containsBlacklist(item.getContent())) {
                log.debug("Sensitive content skipped: {}", item.getTitle());
                blacklisted++;
                continue;
            }

            // 4. HanLP Processing (Segmentation / NER - simplified here)
            // Just demonstrating we can use HanLP
            List<String> keywords = HanLP.extractKeyword(item.getContent(), 5);
            log.debug("Extracted keywords: {}", keywords);

            // 5. Structure
            // We keep the item as is, but maybe clean the text (remove extra spaces)
            item.setContent(item.getContent().replaceAll("\\s+", " ").trim());

            cleaned.add(item);
        }
        log.info("Cleaning summary: input={}, output={}, duplicate={}, tooShort={}, blacklisted={}",
                rawItems.size(), cleaned.size(), duplicate, tooShort, blacklisted);
        return cleaned;
    }

    private boolean containsBlacklist(String content) {
        for (String word : BLACKLIST) {
            if (content.contains(word)) return true;
        }
        return false;
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
