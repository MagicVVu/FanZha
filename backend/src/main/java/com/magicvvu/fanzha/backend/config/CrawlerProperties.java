package com.magicvvu.fanzha.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {
    private boolean enabled = false;
    private boolean preferIpv4 = false;
    private int maxItemsPerRun = 5;
    private long minDelayMs = 1000;
    private long maxDelayMs = 5000;
    private double requestsPerMinute = 12.0d;
    private String checkpointPath = "./data/fraud_crawler_checkpoint.json";
    private boolean requireKeywordMatch = true;
    private boolean allowUnknownPublishTime = true;
    private int maxArticleAgeDays = 90;
    private double minFraudScore = 0.52d;
    private int sourceRepairAlertHours = 24;
    private boolean grayEnhancedFilterEnabled = false;
    /** 搜狗反爬且无列表 fallback 时，是否仍写入占位记录。默认 false 以提升数据质量。 */
    private boolean sogouPlaceholderEnabled = false;
    private List<String> fraudKeywords = new ArrayList<>();
    private List<String> userAgents = new ArrayList<>();
    private Map<String, String> extraHeaders = new HashMap<>();
    private ProxyProperties proxy = new ProxyProperties();
    private CaptchaProperties captcha = new CaptchaProperties();
    private PlaywrightProperties playwright = new PlaywrightProperties();
    /** 搜狗反爬严重时可启用本地 JSON 数据作为搜狗来源补充。仅影响搜狗源，不影响其它站点。 */
    private SogouLocalJsonProperties sogouLocalJson = new SogouLocalJsonProperties();
    private List<SourceProperties> sources = new ArrayList<>();

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String apiUrl;
        private String host;
        private int port;
        private String username;
        private String password;
        private String type = "http";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 8000;
    }

    @Data
    public static class CaptchaProperties {
        private boolean enabled = false;
        private String tessdataPath;
    }

    @Data
    public static class PlaywrightProperties {
        private boolean enabled = false;
        private boolean headless = true;
        private long navigationTimeoutMs = 45000L;
        private long extraSettleMs = 1500L;
        /** 浏览器缓存路径，优先使用该值，避免默认写到 C:\Users\<user>\AppData\Local\ms-playwright */
        private String browsersPath = "";
        /** 共享浏览器用户目录（持久 Cookie/本地存储），可提高反爬场景入库率 */
        private String userDataDir = "./data/playwright-user-data";
        /** load | domcontentloaded | networkidle | commit */
        private String waitUntil = "domcontentloaded";
    }

    @Data
    public static class SourceProperties {
        private String name;
        private String baseUrl;
        private String listUrl;
        private List<String> listUrls = new ArrayList<>();
        /** When the article URL after redirect is on another host (e.g. mp.weixin.qq.com), match this source by host suffix. */
        private List<String> articleHosts = new ArrayList<>();
        private String listLinkSelector;
        private String articleTitleSelector;
        private String articleTimeSelector;
        private String articleContentSelector;
        private String publishTimeFormat;
        private int priority = 0;
        private String strategy = "generic";
        private double trustScore = 0.6d;
        private int updateFrequencyMinutes = 60;
    }

    @Data
    public static class SogouLocalJsonProperties {
        /** 开启后，搜狗源优先从本地 JSON 文件加载 title/content。 */
        private boolean enabled = false;
        private List<SogouLocalJsonFile> files = new ArrayList<>();
    }

    @Data
    public static class SogouLocalJsonFile {
        /** 本地 JSON 文件路径（支持绝对或相对路径）。 */
        private String path;
        /**
         * 仅应用到这些 source 名称；为空表示对所有搜狗 source 生效。
         * 例如：sogou-weixin-taolu / sogou-weixin-fanzha
         */
        private List<String> sourceNames = new ArrayList<>();
    }
}
