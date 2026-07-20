package com.magicvvu.fanzha.backend.config;

import com.magicvvu.fanzha.backend.service.fraud.FraudNewsEtlService;
import com.magicvvu.fanzha.backend.service.fraud.captcha.CaptchaSolver;
import com.magicvvu.fanzha.backend.service.fraud.captcha.Tess4jCaptchaSolver;
import com.magicvvu.fanzha.backend.service.fraud.deepseek.DeepSeekFraudCaseExtractor;
import com.magicvvu.fanzha.backend.service.fraud.http.OkHttpFetchClient;
import com.magicvvu.fanzha.backend.service.fraud.http.PlaywrightArticleFetcher;
import com.magicvvu.fanzha.backend.service.fraud.http.SimpleMemoryCookieJar;
import com.magicvvu.fanzha.backend.service.fraud.proxy.HttpProxyPool;
import com.magicvvu.fanzha.backend.service.fraud.proxy.NoProxyPool;
import com.magicvvu.fanzha.backend.service.fraud.storage.JdbcFraudNewsStore;
import com.magicvvu.fanzha.backend.service.fraud.vector.BgeEmbeddingClient;
import com.magicvvu.fanzha.backend.service.chroma.ChromaService;
import com.magicvvu.fanzha.backend.util.DeepSeekClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties({
        CrawlerProperties.class,
        FraudPipelineProperties.class,
        EmbeddingProperties.class,
        VectorStoreProperties.class,
        FraudNewsStorageProperties.class
})
@RequiredArgsConstructor
public class CrawlerModuleConfig {

    @Bean
    public OkHttpClient crawlerOkHttpClient(CrawlerProperties properties) {
        CookieJar cookieJar = new SimpleMemoryCookieJar();
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                ;

        if (properties.isPreferIpv4()) {
            builder.dns(hostname -> {
                List<InetAddress> all = Dns.SYSTEM.lookup(hostname);
                List<InetAddress> ipv4 = new ArrayList<>();
                List<InetAddress> rest = new ArrayList<>();
                for (InetAddress addr : all) {
                    if (addr instanceof Inet4Address) {
                        ipv4.add(addr);
                    } else {
                        rest.add(addr);
                    }
                }
                ipv4.addAll(rest);
                return ipv4;
            });
        }

        return builder.build();
    }

    @Bean
    public HttpProxyPool proxyPool(CrawlerProperties properties, OkHttpClient okHttpClient) {
        if (properties.getProxy() != null && properties.getProxy().isEnabled()) {
            OkHttpClient proxyClient = okHttpClient.newBuilder()
                    .connectTimeout(properties.getProxy().getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                    .readTimeout(properties.getProxy().getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                    .build();
            return new HttpProxyPool(properties.getProxy(), proxyClient);
        }
        return new NoProxyPool();
    }

    @Bean
    public OkHttpFetchClient fetchClient(CrawlerProperties properties, OkHttpClient okHttpClient, HttpProxyPool proxyPool) {
        return new OkHttpFetchClient(properties, okHttpClient, proxyPool);
    }

    @Bean
    public PlaywrightArticleFetcher playwrightArticleFetcher(CrawlerProperties properties) {
        return new PlaywrightArticleFetcher(properties);
    }

    @Bean
    public CaptchaSolver captchaSolver(CrawlerProperties properties) {
        if (properties.getCaptcha() != null && properties.getCaptcha().isEnabled()) {
            return new Tess4jCaptchaSolver(properties.getCaptcha().getTessdataPath());
        }
        return bytes -> "";
    }

    @Bean
    public JdbcFraudNewsStore fraudNewsStore(JdbcTemplate jdbcTemplate, FraudNewsStorageProperties properties) {
        return new JdbcFraudNewsStore(jdbcTemplate, properties);
    }

    @Bean
    public DeepSeekFraudCaseExtractor fraudCaseExtractor(DeepSeekClient deepSeekClient, FraudPipelineProperties properties) {
        return new DeepSeekFraudCaseExtractor(deepSeekClient, properties);
    }

    @Bean
    public BgeEmbeddingClient embeddingClient(EmbeddingProperties properties, OkHttpClient okHttpClient) {
        return new BgeEmbeddingClient(properties, okHttpClient);
    }

    @Bean
    public FraudNewsEtlService fraudNewsEtlService(
            CrawlerProperties crawlerProperties,
            OkHttpFetchClient fetchClient,
            PlaywrightArticleFetcher playwrightArticleFetcher,
            JdbcFraudNewsStore fraudNewsStore,
            DeepSeekFraudCaseExtractor extractor,
            BgeEmbeddingClient embeddingClient,
            ChromaService chromaService,
            ObjectMapper objectMapper
    ) {
        return new FraudNewsEtlService(crawlerProperties, fetchClient, playwrightArticleFetcher, fraudNewsStore, extractor, embeddingClient, chromaService, objectMapper);
    }
}
