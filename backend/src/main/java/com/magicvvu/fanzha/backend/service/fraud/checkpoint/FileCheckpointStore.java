package com.magicvvu.fanzha.backend.service.fraud.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RequiredArgsConstructor
public class FileCheckpointStore {
    private final String path;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrawlerCheckpoint load() {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                return new CrawlerCheckpoint();
            }
            byte[] bytes = Files.readAllBytes(p);
            if (bytes.length == 0) {
                return new CrawlerCheckpoint();
            }
            return objectMapper.readValue(bytes, CrawlerCheckpoint.class);
        } catch (Exception e) {
            return new CrawlerCheckpoint();
        }
    }

    public void save(CrawlerCheckpoint checkpoint) {
        try {
            Path p = Paths.get(path);
            Path parent = p.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(checkpoint);
            Files.write(p, bytes);
        } catch (Exception ignored) {
        }
    }
}
