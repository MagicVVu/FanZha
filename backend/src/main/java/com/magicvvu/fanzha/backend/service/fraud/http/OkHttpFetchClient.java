package com.magicvvu.fanzha.backend.service.fraud.http;

import com.magicvvu.fanzha.backend.config.CrawlerProperties;
import com.magicvvu.fanzha.backend.service.fraud.proxy.HttpProxyPool;
import com.magicvvu.fanzha.backend.service.fraud.proxy.ProxyInfo;
import com.magicvvu.fanzha.backend.service.fraud.util.Retryer;
import lombok.RequiredArgsConstructor;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
public class OkHttpFetchClient {

    private final CrawlerProperties properties;
    private final OkHttpClient okHttpClient;
    private final HttpProxyPool proxyPool;

    public FetchResult get(String url) throws Exception {
        return get(url, null);
    }

    public FetchResult get(String url, String referer) throws Exception {
        return Retryer.execute("http-get", 3, 200L, () -> doGet(url, referer));
    }

    private FetchResult doGet(String url, String referer) throws Exception {
        String ua = pickUserAgent(properties.getUserAgents());
        ProxyInfo proxyInfo = null;
        if (properties.getProxy() != null && properties.getProxy().isEnabled()) {
            proxyInfo = proxyPool.pickProxy();
            if (proxyInfo == null && properties.getProxy().getHost() != null && !properties.getProxy().getHost().trim().isEmpty() && properties.getProxy().getPort() > 0) {
                proxyInfo = new ProxyInfo(
                        properties.getProxy().getHost().trim(),
                        properties.getProxy().getPort(),
                        properties.getProxy().getUsername(),
                        properties.getProxy().getPassword()
                );
            }
        }

        OkHttpClient client = okHttpClient;
        if (proxyInfo != null) {
            final ProxyInfo selectedProxy = proxyInfo;
            Proxy.Type proxyType = Proxy.Type.HTTP;
            if (properties.getProxy() != null && properties.getProxy().getType() != null) {
                String type = properties.getProxy().getType().trim().toLowerCase();
                if (type.startsWith("socks")) {
                    proxyType = Proxy.Type.SOCKS;
                }
            }
            OkHttpClient.Builder builder = okHttpClient.newBuilder()
                    .proxy(new Proxy(proxyType, new InetSocketAddress(selectedProxy.getHost(), selectedProxy.getPort())));
            if (selectedProxy.getUsername() != null && !selectedProxy.getUsername().trim().isEmpty()) {
                final String proxyUsername = selectedProxy.getUsername();
                final String proxyPassword = selectedProxy.getPassword() == null ? "" : selectedProxy.getPassword();
                builder.proxyAuthenticator((route, response) -> {
                    String credential = Credentials.basic(proxyUsername, proxyPassword);
                    return response.request().newBuilder().header("Proxy-Authorization", credential).build();
                });
            }
            client = builder.build();
        }

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Upgrade-Insecure-Requests", "1");
        if (referer != null && !referer.trim().isEmpty()) {
            requestBuilder.header("Referer", referer.trim());
        }
        if (properties.getExtraHeaders() != null && !properties.getExtraHeaders().isEmpty()) {
            for (Map.Entry<String, String> entry : properties.getExtraHeaders().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if (k == null || k.trim().isEmpty() || v == null || v.trim().isEmpty()) {
                    continue;
                }
                requestBuilder.header(k.trim(), v.trim());
            }
        }
        Request request = requestBuilder.build();

        Response response = client.newCall(request).execute();
        String body = response.body() != null ? response.body().string() : "";
        String finalUrl = response.request() != null && response.request().url() != null ? response.request().url().toString() : url;
        String contentType = response.body() != null && response.body().contentType() != null ? response.body().contentType().toString() : null;
        return new FetchResult(url, response.code(), finalUrl, contentType, body);
    }

    private static String pickUserAgent(List<String> userAgents) {
        if (userAgents == null || userAgents.isEmpty()) {
            return "Mozilla/5.0";
        }
        return userAgents.get(ThreadLocalRandom.current().nextInt(userAgents.size()));
    }
}
