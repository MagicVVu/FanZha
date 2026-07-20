package com.magicvvu.fanzha.backend.util;

import com.magicvvu.fanzha.backend.config.DeepSeekConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DeepSeekClient {
    private final RestTemplate deepSeekRestTemplate;
    private final DeepSeekConfig.DeepSeekProperties properties;

    // Chat completion
    public String chat(String system, String user, String modelOverride) {
        String apiKey = properties.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY");
        }

        String model = StringUtils.hasText(modelOverride) ? modelOverride : properties.getModel();
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("未配置 deepseek.model");
        }

        List<ChatMessage> messages = new ArrayList<>();
        if (StringUtils.hasText(system)) {
            messages.add(new ChatMessage("system", system));
        }
        messages.add(new ChatMessage("user", user));

        ChatCompletionsRequest request = new ChatCompletionsRequest(model, messages);

        HttpHeaders headers = createHeaders(apiKey);
        String url = normalizeBaseUrl(properties.getBaseUrl()) + "/chat/completions";

        try {
            ResponseEntity<ChatCompletionsResponse> response = deepSeekRestTemplate.postForEntity(
                    url,
                    new HttpEntity<>(request, headers),
                    ChatCompletionsResponse.class
            );

            ChatCompletionsResponse body = response.getBody();
            if (body == null || body.getChoices() == null || body.getChoices().isEmpty()) {
                throw new IllegalStateException("DeepSeek 响应为空");
            }

            Choice first = body.getChoices().get(0);
            if (first == null || first.getMessage() == null || !StringUtils.hasText(first.getMessage().getContent())) {
                throw new IllegalStateException("DeepSeek 响应缺少内容");
            }

            return first.getMessage().getContent();
        } catch (HttpStatusCodeException e) {
            handleError(e);
            return null; // Unreachable
        }
    }

    // Embeddings
    public List<Float> embed(String input) {
        String apiKey = properties.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY");
        }

        String model = properties.getEmbeddingModel();
        if (!StringUtils.hasText(model)) {
            model = "deepseek-embedding";
        }

        EmbeddingRequest request = new EmbeddingRequest(model, input);
        HttpHeaders headers = createHeaders(apiKey);
        String url = normalizeBaseUrl(properties.getBaseUrl()) + "/embeddings";

        try {
            ResponseEntity<EmbeddingResponse> response = deepSeekRestTemplate.postForEntity(
                    url,
                    new HttpEntity<>(request, headers),
                    EmbeddingResponse.class
            );

            EmbeddingResponse body = response.getBody();
            if (body == null || body.getData() == null || body.getData().isEmpty()) {
                throw new IllegalStateException("DeepSeek Embedding 响应为空");
            }

            return body.getData().get(0).getEmbedding();
        } catch (HttpStatusCodeException e) {
            handleError(e);
            return Collections.emptyList(); // Unreachable
        }
    }

    private HttpHeaders createHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(apiKey.trim());
        return headers;
    }

    private void handleError(HttpStatusCodeException e) {
        String resp = e.getResponseBodyAsString();
        if (StringUtils.hasText(resp)) {
            throw new IllegalStateException("DeepSeek 请求失败: " + e.getStatusCode().value() + " " + resp);
        }
        throw new IllegalStateException("DeepSeek 请求失败: " + e.getStatusCode().value());
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.deepseek.com";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Data
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;
        private String content;
    }

    @Data
    @AllArgsConstructor
    public static class ChatCompletionsRequest {
        private String model;
        private List<ChatMessage> messages;
    }

    @Data
    public static class ChatCompletionsResponse {
        private List<Choice> choices;
    }

    @Data
    public static class Choice {
        private ChatMessage message;
    }

    @Data
    @AllArgsConstructor
    public static class EmbeddingRequest {
        private String model;
        private String input;
    }

    @Data
    public static class EmbeddingResponse {
        private List<EmbeddingData> data;
    }

    @Data
    public static class EmbeddingData {
        private List<Float> embedding;
        private int index;
    }
}
