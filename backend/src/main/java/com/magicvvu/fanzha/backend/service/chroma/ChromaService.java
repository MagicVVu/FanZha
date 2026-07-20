package com.magicvvu.fanzha.backend.service.chroma;

import com.magicvvu.fanzha.backend.config.ChromaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChromaService {

    private final ChromaConfig chromaConfig;
    private final RestTemplate restTemplate;
    private final Map<String, String> v2CollectionIdCache = new ConcurrentHashMap<>();

    private String getV2BaseUrl() {
        return "http://" + chromaConfig.getHost() + ":" + chromaConfig.getPort() + "/api/v2";
    }

    private String getV1BaseUrl() {
        return "http://" + chromaConfig.getHost() + ":" + chromaConfig.getPort() + "/api/v1";
    }

    private String getV2TenantDbBaseUrl() {
        return getV2BaseUrl()
                + "/tenants/" + chromaConfig.getTenant()
                + "/databases/" + chromaConfig.getDatabase();
    }

    private String getV1TenantDbBaseUrl() {
        return getV1BaseUrl()
                + "/tenants/" + chromaConfig.getTenant()
                + "/databases/" + chromaConfig.getDatabase();
    }

    @PostConstruct
    public void init() {
        if (chromaConfig.isAutoInit()) {
            ensureCollectionExists();
        }
    }

    public void ensureCollectionExists() {
        ensureCollectionExists(chromaConfig.getCollectionName());
    }

    public void ensureCollectionExists(String name) {

        int maxAttempts = 10;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ensureCollectionExistsOnce(name);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    log.error("Failed to ensure Chroma collection exists", e);
                    return;
                }
                log.warn("Chroma not ready or API mismatch (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Failed to ensure Chroma collection exists", e);
                    return;
                }
            }
        }
    }

    private void ensureCollectionExistsOnce(String name) {
        try {
            ensureCollectionExistsWithBase(getV2TenantDbBaseUrl(), name);
            cacheV2CollectionId(name);
            return;
        } catch (HttpStatusCodeException e) {
            log.warn("Chroma v2 API failed (status {}), falling back to v1 API", e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Chroma v2 API failed, falling back to v1 API: {}", e.getMessage());
        }
        ensureCollectionExistsWithBase(getV1TenantDbBaseUrl(), name);
    }

    private void ensureCollectionExistsWithBase(String baseUrl, String name) {
        String collectionsUrl = baseUrl + "/collections";

        try {
            restTemplate.getForObject(collectionsUrl + "/" + name, Map.class);
            log.info("Chroma Collection '{}' already exists.", name);
            return;
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Chroma Collection '{}' not found, creating...", name);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("hnsw:space", "cosine");
        metadata.put("hnsw:construction_ef", 512);
        metadata.put("hnsw:M", 16);
        body.put("metadata", metadata);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            restTemplate.postForEntity(collectionsUrl, new HttpEntity<>(body, headers), Map.class);
            log.info("Chroma Collection '{}' created successfully.", name);
        } catch (HttpStatusCodeException e) {
            String resp = e.getResponseBodyAsString();
            if (resp != null && !resp.trim().isEmpty()) {
                throw new IllegalStateException("Chroma create collection failed: " + e.getStatusCode().value() + " " + resp, e);
            }
            throw new IllegalStateException("Chroma create collection failed: " + e.getStatusCode().value(), e);
        }
    }

    public void upsert(List<String> ids, List<List<Float>> embeddings, List<String> documents, List<Map<String, Object>> metadatas) {
        upsert(chromaConfig.getCollectionName(), ids, embeddings, documents, metadatas);
    }

    public void upsert(String collectionName, List<String> ids, List<List<Float>> embeddings, List<String> documents, List<Map<String, Object>> metadatas) {
        if (ids.isEmpty()) return;

        try {
            upsertWithV2(collectionName, ids, embeddings, documents, metadatas);
        } catch (Exception e) {
            log.warn("Chroma upsert with v2 API failed, falling back to v1 API: {}", e.getMessage());
            upsertWithBase(getV1TenantDbBaseUrl(), collectionName, ids, embeddings, documents, metadatas);
        }
    }

    private void upsertWithV2(String collectionName, List<String> ids, List<List<Float>> embeddings, List<String> documents, List<Map<String, Object>> metadatas) {
        String collectionId = resolveV2CollectionId(collectionName);
        upsertWithBase(getV2TenantDbBaseUrl(), collectionId, ids, embeddings, documents, metadatas);
    }

    private void upsertWithBase(String baseUrl, String collectionName, List<String> ids, List<List<Float>> embeddings, List<String> documents, List<Map<String, Object>> metadatas) {
        String url = baseUrl + "/collections/" + collectionName + "/upsert";

        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("embeddings", embeddings);
        body.put("documents", documents);
        body.put("metadatas", metadatas);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            log.info("Upserted {} items to Chroma.", ids.size());
        } catch (HttpStatusCodeException e) {
            String resp = e.getResponseBodyAsString();
            if (resp != null && !resp.trim().isEmpty()) {
                throw new IllegalStateException("Chroma upsert failed: " + e.getStatusCode().value() + " " + resp, e);
            }
            throw new IllegalStateException("Chroma upsert failed: " + e.getStatusCode().value(), e);
        }
    }

    public List<String> query(List<Float> queryEmbeddings, int nResults) {
        return query(chromaConfig.getCollectionName(), queryEmbeddings, nResults);
    }

    public List<String> query(String collectionName, List<Float> queryEmbeddings, int nResults) {
        try {
            String collectionId = resolveV2CollectionId(collectionName);
            return queryWithBase(getV2TenantDbBaseUrl(), collectionId, queryEmbeddings, nResults);
        } catch (Exception e) {
            log.warn("Chroma query with v2 API failed, falling back to v1 API: {}", e.getMessage());
            try {
                return queryWithBase(getV1TenantDbBaseUrl(), collectionName, queryEmbeddings, nResults);
            } catch (Exception e2) {
                log.error("Failed to query Chroma", e2);
                return Collections.emptyList();
            }
        }
    }

    private void cacheV2CollectionId(String collectionName) {
        try {
            String collectionId = resolveV2CollectionIdByGet(collectionName);
            if (collectionId != null && !collectionId.trim().isEmpty()) {
                v2CollectionIdCache.put(collectionName, collectionId);
            }
        } catch (Exception e) {
            log.debug("Failed to cache v2 collection id for {}: {}", collectionName, e.getMessage());
        }
    }

    private String resolveV2CollectionId(String collectionName) {
        String cached = v2CollectionIdCache.get(collectionName);
        if (cached != null && !cached.trim().isEmpty()) {
            return cached;
        }
        String fromGet = resolveV2CollectionIdByGet(collectionName);
        if (fromGet != null && !fromGet.trim().isEmpty()) {
            v2CollectionIdCache.put(collectionName, fromGet);
            return fromGet;
        }
        throw new IllegalStateException("Failed to resolve Chroma v2 collection id for name: " + collectionName);
    }

    private String resolveV2CollectionIdByGet(String collectionName) {
        String url = getV2TenantDbBaseUrl() + "/collections/" + collectionName;
        Map result = restTemplate.getForObject(url, Map.class);
        if (result == null) {
            return null;
        }
        Object idObj = result.get("id");
        if (idObj == null) {
            return null;
        }
        String id = String.valueOf(idObj).trim();
        if (id.isEmpty()) {
            return null;
        }
        return id;
    }

    private List<String> queryWithBase(String baseUrl, String collectionName, List<Float> queryEmbeddings, int nResults) {
        String url = baseUrl + "/collections/" + collectionName + "/query";

        Map<String, Object> body = new HashMap<>();
        body.put("query_embeddings", Collections.singletonList(queryEmbeddings));
        body.put("n_results", nResults);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
        Map result = response.getBody();
        if (result != null && result.containsKey("documents")) {
            List<List<String>> docs = (List<List<String>>) result.get("documents");
            if (!docs.isEmpty()) return docs.get(0);
        }
        return Collections.emptyList();
    }
}
