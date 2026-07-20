package com.magicvvu.fanzha.backend.service;

import com.magicvvu.fanzha.backend.service.chroma.ChromaService;
import com.magicvvu.fanzha.backend.service.cleaning.DataCleaningService;
import com.magicvvu.fanzha.backend.service.crawler.CrawlerService;
import com.magicvvu.fanzha.backend.service.crawler.NewsItem;
import com.magicvvu.fanzha.backend.util.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseEtlService {

    private final CrawlerService crawlerService;
    private final DataCleaningService dataCleaningService;
    private final DeepSeekClient deepSeekClient;
    private final ChromaService chromaService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    @Value("${line1.enabled:false}")
    private boolean enabled;

    // Cron: 0 30 2 * * ? (2:30 AM daily), default to every 5 mins for demo
    @Scheduled(cron = "${scheduler.cron:0 0/5 * * * ?}")
    public void runEtlJob() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("ETL Job is already running, skipping.");
            return;
        }

        log.info("Starting ETL Job...");
        long start = System.currentTimeMillis();

        try {
            List<NewsItem> raw = crawlerService.crawlBaiduNews();
            log.info("Crawled {} items.", raw.size());
            if (raw.isEmpty()) {
                log.warn("Crawler returned 0 items. ETL finished.");
                return;
            }

            List<NewsItem> cleaned = dataCleaningService.clean(raw);
            log.info("Cleaned {} items.", cleaned.size());
            if (cleaned.isEmpty()) {
                log.warn("All crawled items were filtered out by cleaning rules. ETL finished.");
                return;
            }

            int batchSize = 50;
            for (int i = 0; i < cleaned.size(); i += batchSize) {
                List<NewsItem> batch = cleaned.subList(i, Math.min(i + batchSize, cleaned.size()));
                processBatch(batch);
            }
        } catch (Exception e) {
            log.error("ETL Job failed.", e);
        } finally {
            long end = System.currentTimeMillis();
            log.info("ETL Job finished in {} ms.", (end - start));
            running.set(false);
        }
    }

    private void processBatch(List<NewsItem> batch) {
        List<String> ids = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        List<List<Float>> embeddings = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();

        for (NewsItem item : batch) {
            try {
                // Generate ID (MD5 of URL or UUID)
                String id = UUID.nameUUIDFromBytes(item.getUrl().getBytes()).toString();

                // Embed Content (Use summary or content)
                // Truncate to avoid token limit if necessary (DeepSeek usually 8k, but embedding might differ)
                String textToEmbed = item.getContent().length() > 8000 ? item.getContent().substring(0, 8000) : item.getContent();

                List<Float> vector = deepSeekClient.embed(textToEmbed);
                if (vector.isEmpty()) {
                    log.warn("Empty embedding for item: {}", item.getTitle());
                    continue;
                }

                ids.add(id);
                documents.add(item.getContent());
                embeddings.add(vector);

                Map<String, Object> meta = new HashMap<>();
                meta.put("title", item.getTitle());
                meta.put("url", item.getUrl());
                meta.put("source", item.getSource());
                meta.put("publish_time", item.getPublishTime().toString());
                metadatas.add(meta);

            } catch (IllegalStateException e) {
                log.error("Embedding failed: {}", e.getMessage());
                throw e;
            } catch (Exception e) {
                log.error("Failed to process item: " + item.getTitle(), e);
            }
        }

        if (!ids.isEmpty()) {
            chromaService.upsert(ids, embeddings, documents, metadatas);
        }
    }
}
