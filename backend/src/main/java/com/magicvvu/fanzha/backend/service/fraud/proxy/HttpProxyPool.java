package com.magicvvu.fanzha.backend.service.fraud.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magicvvu.fanzha.backend.config.CrawlerProperties;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
public class HttpProxyPool {
    private final CrawlerProperties.ProxyProperties properties;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProxyInfo pickProxy() {
        String apiUrl = properties.getApiUrl();
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            return null;
        }

        try {
            Request request = new Request.Builder().url(apiUrl).get().build();
            Response response = okHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            if (body.trim().isEmpty()) {
                return null;
            }

            JsonNode node = objectMapper.readTree(body);
            List<ProxyInfo> proxies = parseProxies(node);
            if (proxies.isEmpty()) {
                return null;
            }
            return proxies.get(ThreadLocalRandom.current().nextInt(proxies.size()));
        } catch (Exception e) {
            return null;
        }
    }

    private List<ProxyInfo> parseProxies(JsonNode node) {
        List<ProxyInfo> proxies = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                ProxyInfo proxy = parseProxyItem(item);
                if (proxy != null) proxies.add(proxy);
            }
            return proxies;
        }
        if (node.isObject() && node.has("data") && node.get("data").isArray()) {
            for (JsonNode item : node.get("data")) {
                ProxyInfo proxy = parseProxyItem(item);
                if (proxy != null) proxies.add(proxy);
            }
            return proxies;
        }
        ProxyInfo single = parseProxyItem(node);
        if (single != null) proxies.add(single);
        return proxies;
    }

    private ProxyInfo parseProxyItem(JsonNode item) {
        if (item == null) {
            return null;
        }

        if (item.isTextual()) {
            String text = item.asText();
            String[] parts = text.split(":");
            if (parts.length == 2) {
                try {
                    return new ProxyInfo(parts[0].trim(), Integer.parseInt(parts[1].trim()), null, null);
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }

        if (!item.isObject()) {
            return null;
        }

        String host = getText(item, "host");
        Integer port = getInt(item, "port");
        if (host == null || port == null) {
            String proxy = getText(item, "proxy");
            if (proxy != null) {
                String[] parts = proxy.split(":");
                if (parts.length == 2) {
                    try {
                        return new ProxyInfo(parts[0].trim(), Integer.parseInt(parts[1].trim()), null, null);
                    } catch (Exception ignored) {
                        return null;
                    }
                }
            }
            return null;
        }
        String username = getText(item, "username");
        String password = getText(item, "password");
        return new ProxyInfo(host, port, username, password);
    }

    private static String getText(JsonNode node, String field) {
        return node.has(field) && node.get(field).isTextual() ? node.get(field).asText() : null;
    }

    private static Integer getInt(JsonNode node, String field) {
        return node.has(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : null;
    }
}
