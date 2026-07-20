package com.magicvvu.fanzha.backend.service.fraud.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magicvvu.fanzha.backend.config.EmbeddingProperties;
import com.magicvvu.fanzha.backend.service.fraud.util.Retryer;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class BgeEmbeddingClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final EmbeddingProperties properties;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Float> embedSingle(String text) throws Exception {
        List<List<Float>> vectors = embedBatch(java.util.Collections.singletonList(text));
        if (vectors.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return vectors.get(0);
    }

    public List<List<Float>> embedBatch(List<String> texts) throws Exception {
        return Retryer.execute("embedding", 5, 300L, () -> doEmbed(texts));
    }

    private List<List<Float>> doEmbed(List<String> texts) throws Exception {
        String url = properties.getBaseUrl();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url = url + "/embed";

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", properties.getModel());
        payload.put("texts", texts);
        payload.put("dim", properties.getDim());

        String json = objectMapper.writeValueAsString(payload);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, json))
                .build();

        Response response = okHttpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            throw new IllegalStateException("Embedding service error: " + response.code());
        }
        String body = response.body() != null ? response.body().string() : "";
        JsonNode node = objectMapper.readTree(body);
        JsonNode vectorsNode = node.get("vectors");
        if (vectorsNode == null || !vectorsNode.isArray()) {
            return java.util.Collections.emptyList();
        }

        List<List<Float>> vectors = new ArrayList<>();
        for (JsonNode vecNode : vectorsNode) {
            if (!vecNode.isArray()) continue;
            List<Float> vec = new ArrayList<>();
            for (JsonNode v : vecNode) {
                vec.add((float) v.asDouble());
            }
            vectors.add(vec);
        }
        return vectors;
    }
}
