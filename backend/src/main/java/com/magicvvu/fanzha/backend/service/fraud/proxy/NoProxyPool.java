package com.magicvvu.fanzha.backend.service.fraud.proxy;

import com.magicvvu.fanzha.backend.config.CrawlerProperties;
import okhttp3.OkHttpClient;

public class NoProxyPool extends HttpProxyPool {
    public NoProxyPool() {
        super(new CrawlerProperties.ProxyProperties(), new OkHttpClient());
    }

    @Override
    public ProxyInfo pickProxy() {
        return null;
    }
}
