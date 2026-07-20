package com.magicvvu.fanzha.backend.service.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.RateLimiter;
import com.magicvvu.fanzha.backend.config.CrawlerProperties;
import com.magicvvu.fanzha.backend.service.fraud.checkpoint.CrawlerCheckpoint;
import com.magicvvu.fanzha.backend.service.fraud.checkpoint.FileCheckpointStore;
import com.magicvvu.fanzha.backend.service.fraud.deepseek.DeepSeekFraudCaseExtractor;
import com.magicvvu.fanzha.backend.service.fraud.deepseek.FraudCaseExtraction;
import com.magicvvu.fanzha.backend.service.fraud.http.FetchResult;
import com.magicvvu.fanzha.backend.service.fraud.http.OkHttpFetchClient;
import com.magicvvu.fanzha.backend.service.fraud.http.PlaywrightArticleFetcher;
import com.magicvvu.fanzha.backend.service.fraud.storage.FraudNewsRow;
import com.magicvvu.fanzha.backend.service.fraud.storage.JdbcFraudNewsStore;
import com.magicvvu.fanzha.backend.service.fraud.util.HashUtil;
import com.magicvvu.fanzha.backend.service.fraud.vector.BgeEmbeddingClient;
import com.magicvvu.fanzha.backend.service.chroma.ChromaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class FraudNewsEtlService {
    private static final String LOCAL_SOGOU_URL_PREFIX = "local-sogou://";
    /** 过短多为导航/异常页；略放宽以减少误杀 */
    private static final int MIN_ARTICLE_CONTENT_LENGTH = 30;
    private static final List<String> DEFAULT_CORE_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "诈骗", "骗", "骗局", "骗子", "行骗", "诈"
    ));
    private static final List<String> DEFAULT_TYPE_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "电信", "网络", "刷单", "杀猪盘", "投资理财", "冒充客服", "冒充公检法", "虚假投资", "网恋", "贷款", "征信", "养老", "兼职", "返利", "洗钱", "非法集资"
    ));
    private static final List<String> DEFAULT_NEWS_CONTEXT_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "警惕", "提醒", "警方", "破案", "抓获", "曝光", "案例", "防骗", "通报", "立案", "侦破", "辟谣",
            "反诈", "劝阻", "预警", "套路", "宣传"
    ));
    private static final List<String> NOISE_BLOCK_HINTS = Collections.unmodifiableList(Arrays.asList(
            "广告", "推荐", "相关阅读", "相关链接", "延伸阅读", "责任编辑", "上一篇", "下一篇", "免责声明"
    ));
    private static final Pattern DATE_TIME_PATTERN = Pattern.compile("(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}(?:\\s*日)?(?:\\s+\\d{1,2}:\\d{1,2}(?::\\d{1,2})?)?)");
    private static final double DEFAULT_MIN_FRAUD_SCORE = 0.52d;
    private static final double RELAXED_RELEVANCE_FLOOR = 0.18d;
    private static final int SUMMARY_MAX_LENGTH = 1200;
    private static final List<String> FRAUD_SEMANTIC_PROTOTYPES = Collections.unmodifiableList(Arrays.asList(
            "冒充公检法要求转账",
            "刷单返利诈骗诱导下载应用",
            "虚假投资理财平台引导充值",
            "冒充客服退款并索取验证码",
            "网络贷款诈骗先收费后放款",
            "恋爱交友诱导转账杀猪盘",
            "非法集资承诺高收益后跑路"
    ));
    private static final Map<String, Double> FRAUD_FEATURE_WEIGHTS = createFraudFeatureWeights();

    private final CrawlerProperties crawlerProperties;
    private final OkHttpFetchClient fetchClient;
    private final PlaywrightArticleFetcher playwrightArticleFetcher;
    private final JdbcFraudNewsStore fraudNewsStore;
    private final DeepSeekFraudCaseExtractor extractor;
    private final BgeEmbeddingClient embeddingClient;
    private final ChromaService chromaService;
    private final ObjectMapper objectMapper;

    @Value("${vector-store.chroma.collection:fraud_cases_v2}")
    private String chromaCollection;

    @Value("${embedding.dim:1024}")
    private int embeddingDim;

    private final AtomicBoolean chromaEnsured = new AtomicBoolean(false);
    /** key=local-sogou://... synthetic url */
    private final Map<String, LocalSogouArticle> localSogouArticleIndex = new ConcurrentHashMap<>();
    /** key=sourceName */
    private final Map<String, LocalSogouSourceCache> localSogouSourceCache = new ConcurrentHashMap<>();

    private final RateLimiter rateLimiter = RateLimiter.create(1.0d);

    private enum ProcessResult {
        STORED,
        SKIPPED,
        FAILED
    }

    @Scheduled(cron = "${crawler.cron:0 30 2 * * ?}")
    public void scheduledRun() {
        if (!crawlerProperties.isEnabled()) {
            return;
        }
        runOnce();
    }

    public void runOnce() {
        String batchId = UUID.randomUUID().toString();
        MDC.put("batchId", batchId);
        long start = System.currentTimeMillis();
        try {
            log.info("Fraud crawler batch started.");
            executeBatch();
        } catch (Exception e) {
            log.error("Fraud crawler batch failed.", e);
        } finally {
            log.info("Fraud crawler batch finished in {} ms.", System.currentTimeMillis() - start);
            MDC.remove("batchId");
        }
    }

    private void executeBatch() throws Exception {
        double perMinute = crawlerProperties.getRequestsPerMinute() > 0 ? crawlerProperties.getRequestsPerMinute() : 12.0d;
        rateLimiter.setRate(Math.max(1.0d / 60.0d, perMinute / 60.0d));
        if (!fraudNewsStore.isDatabaseReady()) {
            log.error("Fraud crawler stopped: database not ready or schema check failed.");
            return;
        }
        if (chromaEnsured.compareAndSet(false, true)) {
            try {
                chromaService.ensureCollectionExists(chromaCollection);
            } catch (Exception e) {
                log.warn("Chroma collection init failed (MySQL ingest will still run): {}", e.getMessage());
            }
        }
        FileCheckpointStore checkpointStore = new FileCheckpointStore(crawlerProperties.getCheckpointPath());
        CrawlerCheckpoint checkpoint = checkpointStore.load();

        List<CrawlerProperties.SourceProperties> sources = new ArrayList<>(crawlerProperties.getSources());
        sources.sort(Comparator.comparingInt(CrawlerProperties.SourceProperties::getPriority).reversed());

        ensureCheckpointIntegrity(checkpoint);
        purgeInvalidSeenUrls(checkpoint);
        Map<String, Integer> sourceCandidateCount = new HashMap<>();
        int globalCap = Math.max(1, crawlerProperties.getMaxItemsPerRun());
        int basePerSourceCap = Math.max(1, (globalCap + Math.max(1, sources.size()) - 1) / Math.max(1, sources.size()));
        List<ArrayDeque<String>> perSourceQueues = new ArrayList<>();
        Map<String, Integer> sourceTakeLimit = new HashMap<>();
        Map<String, Integer> sourceTaken = new HashMap<>();
        for (CrawlerProperties.SourceProperties source : sources) {
            List<String> fetchedUrls = fetchListUrls(source, checkpoint);
            sourceCandidateCount.put(source.getName(), fetchedUrls.size());
            ArrayDeque<String> dq = new ArrayDeque<>();
            for (String u : fetchedUrls) {
                String normalized = normalizeUrl(u);
                if (StringUtils.hasText(normalized)) {
                    dq.addLast(normalized);
                }
            }
            perSourceQueues.add(dq);
            int failures = checkpoint.getSourceConsecutiveFailures().getOrDefault(source.getName(), 0);
            int limit = basePerSourceCap;
            if (isSogouSource(source)) {
                // 动态降权：搜狗连续失败多时先把配额让给其它站点，提高总体入库率
                if (failures >= 6) {
                    limit = 0;
                } else if (failures >= 3) {
                    limit = 1;
                }
            }
            sourceTakeLimit.put(source.getName(), Math.max(0, limit));
            sourceTaken.put(source.getName(), 0);
        }
        int allocatedLimits = 0;
        for (CrawlerProperties.SourceProperties source : sources) {
            allocatedLimits += sourceTakeLimit.getOrDefault(source.getName(), 0);
        }
        // 把被降权后腾出的配额优先分配给非搜狗源
        while (allocatedLimits < globalCap) {
            boolean increased = false;
            for (CrawlerProperties.SourceProperties source : sources) {
                if (allocatedLimits >= globalCap) {
                    break;
                }
                String name = source.getName();
                int candidateCount = sourceCandidateCount.getOrDefault(name, 0);
                int curLimit = sourceTakeLimit.getOrDefault(name, 0);
                if (candidateCount <= curLimit) {
                    continue;
                }
                if (isSogouSource(source)) {
                    continue;
                }
                sourceTakeLimit.put(name, curLimit + 1);
                allocatedLimits++;
                increased = true;
            }
            if (!increased) {
                // 没有其它源可分配时，再回退给任意可用源
                for (CrawlerProperties.SourceProperties source : sources) {
                    if (allocatedLimits >= globalCap) {
                        break;
                    }
                    String name = source.getName();
                    int candidateCount = sourceCandidateCount.getOrDefault(name, 0);
                    int curLimit = sourceTakeLimit.getOrDefault(name, 0);
                    if (candidateCount <= curLimit) {
                        continue;
                    }
                    sourceTakeLimit.put(name, curLimit + 1);
                    allocatedLimits++;
                    increased = true;
                }
            }
            if (!increased) {
                break;
            }
        }
        Set<String> candidateUrls = new LinkedHashSet<>();
        while (candidateUrls.size() < globalCap) {
            boolean progressed = false;
            for (int i = 0; i < perSourceQueues.size(); i++) {
                ArrayDeque<String> dq = perSourceQueues.get(i);
                if (candidateUrls.size() >= globalCap) {
                    break;
                }
                if (dq.isEmpty()) {
                    continue;
                }
                CrawlerProperties.SourceProperties source = i < sources.size() ? sources.get(i) : null;
                String sourceName = source == null ? "" : source.getName();
                int taken = sourceTaken.getOrDefault(sourceName, 0);
                int limit = sourceTakeLimit.getOrDefault(sourceName, basePerSourceCap);
                if (taken >= limit) {
                    continue;
                }
                boolean added = false;
                while (!dq.isEmpty() && !added) {
                    String normalized = dq.pollFirst();
                    if (!StringUtils.hasText(normalized)) {
                        continue;
                    }
                    if (candidateUrls.add(normalized)) {
                        added = true;
                        sourceTaken.put(sourceName, taken + 1);
                    }
                }
                if (added) {
                    progressed = true;
                }
            }
            if (!progressed) {
                break;
            }
        }
        log.info("Fraud crawler candidate URLs (round-robin across {} sources): {}, sourceTakeLimit={}", sources.size(), candidateUrls.size(), sourceTakeLimit);

        List<String> targets = new ArrayList<>();
        for (String url : candidateUrls) {
            if (targets.size() >= globalCap) break;
            if (checkpoint.getSeenUrls().contains(url)) continue;
            targets.add(url);
        }

        int processed = 0;
        int stored = 0;
        int skipped = 0;
        int failed = 0;
        for (String url : targets) {
            rateLimiter.acquire();
            randomDelay();
            processed++;
            ProcessResult result = processOne(url, sources, checkpoint);
            if (result == ProcessResult.STORED) {
                stored++;
            } else if (result == ProcessResult.SKIPPED) {
                skipped++;
            } else {
                failed++;
            }
            trimSeen(checkpoint);
            checkpoint.setLastRunEpochMs(System.currentTimeMillis());
            checkpointStore.save(checkpoint);
        }
        checkSourceAlerts(sources, sourceCandidateCount, checkpoint);
        checkpointStore.save(checkpoint);
        double successRate = processed == 0 ? 0.0d : (stored * 100.0d / processed);
        log.info("Fraud crawler summary: candidates={}, targets={}, processed={}, stored={}, skipped={}, failed={}, successRate={}%",
                candidateUrls.size(), targets.size(), processed, stored, skipped, failed, String.format("%.2f", successRate));
    }

    private List<String> fetchListUrls(CrawlerProperties.SourceProperties source, CrawlerCheckpoint checkpoint) {
        if (isSogouSource(source)) {
            List<String> localSogouUrls = loadLocalSogouUrls(source);
            if (!localSogouUrls.isEmpty()) {
                markSourceSuccess(source, checkpoint);
                log.info("Use local sogou json source. source={}, candidates={}", source.getName(), localSogouUrls.size());
                return localSogouUrls;
            }
        }
        List<String> urls = new ArrayList<>();
        boolean fetchedAnyListSuccessfully = false;
        List<String> listUrls = resolveListUrls(source);
        for (String listUrl : listUrls) {
            ListFetchOutcome outcome = fetchListUrlsFromOne(source, listUrl);
            urls.addAll(outcome.getUrls());
            fetchedAnyListSuccessfully = fetchedAnyListSuccessfully || outcome.isFetchSucceeded();
            if (!urls.isEmpty()) {
                markSourceSuccess(source, checkpoint);
                return urls;
            }
        }
        if (fetchedAnyListSuccessfully) {
            markSourceSuccess(source, checkpoint);
        } else {
            markSourceFailure(source, checkpoint);
        }
        return urls;
    }

    private List<String> loadLocalSogouUrls(CrawlerProperties.SourceProperties source) {
        CrawlerProperties.SogouLocalJsonProperties localCfg = crawlerProperties.getSogouLocalJson();
        if (localCfg == null || !localCfg.isEnabled() || source == null || !StringUtils.hasText(source.getName())) {
            return Collections.emptyList();
        }
        List<CrawlerProperties.SogouLocalJsonFile> files = localCfg.getFiles();
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }

        List<CrawlerProperties.SogouLocalJsonFile> matchedFiles = new ArrayList<>();
        String sourceName = source.getName();
        StringBuilder signatureBuilder = new StringBuilder();
        for (CrawlerProperties.SogouLocalJsonFile fileCfg : files) {
            if (!localJsonAppliesToSource(fileCfg, sourceName)) {
                continue;
            }
            matchedFiles.add(fileCfg);
            File f = resolveLocalJsonFile(fileCfg.getPath());
            signatureBuilder.append(fileCfg.getPath()).append('|');
            if (f.exists()) {
                signatureBuilder.append(f.lastModified()).append('|').append(f.length());
            } else {
                signatureBuilder.append("missing");
            }
            signatureBuilder.append(';');
        }
        if (matchedFiles.isEmpty()) {
            return Collections.emptyList();
        }

        String signature = signatureBuilder.toString();
        LocalSogouSourceCache cache = localSogouSourceCache.get(sourceName);
        if (cache != null && signature.equals(cache.getSignature())) {
            return cache.getUrls();
        }

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (CrawlerProperties.SogouLocalJsonFile fileCfg : matchedFiles) {
            String path = fileCfg.getPath();
            File f = resolveLocalJsonFile(path);
            if (!f.exists() || !f.isFile()) {
                log.warn("Sogou local json file not found, skip. source={}, path={}", sourceName, path);
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(f);
                String websiteName = safe(root.path("website_name").asText(""));
                JsonNode rows = root.path("source_data");
                if (!rows.isArray()) {
                    log.warn("Sogou local json source_data is not array, skip. path={}", f.getAbsolutePath());
                    continue;
                }
                int accepted = 0;
                for (JsonNode row : rows) {
                    String title = normalizeText(row.path("title").asText(""));
                    String content = normalizeText(row.path("content").asText(""));
                    if (!StringUtils.hasText(content)) {
                        continue;
                    }
                    if (!StringUtils.hasText(title)) {
                        title = StringUtils.hasText(websiteName) ? websiteName : sourceName;
                    }
                    String syntheticUrl = buildLocalSogouUrl(sourceName, f.getAbsolutePath(), title, content);
                    localSogouArticleIndex.put(syntheticUrl, new LocalSogouArticle(
                            syntheticUrl,
                            title,
                            content,
                            websiteName,
                            f.getAbsolutePath()
                    ));
                    urls.add(syntheticUrl);
                    accepted++;
                }
                log.info("Loaded local sogou json items. source={}, file={}, accepted={}", sourceName, f.getName(), accepted);
            } catch (Exception e) {
                log.warn("Failed to parse local sogou json. source={}, path={}, err={}", sourceName, f.getAbsolutePath(), e.getMessage());
            }
        }

        List<String> result = new ArrayList<>(urls);
        localSogouSourceCache.put(sourceName, new LocalSogouSourceCache(signature, result));
        return result;
    }

    private static boolean localJsonAppliesToSource(CrawlerProperties.SogouLocalJsonFile fileCfg, String sourceName) {
        if (fileCfg == null || !StringUtils.hasText(fileCfg.getPath())) {
            return false;
        }
        List<String> sourceNames = fileCfg.getSourceNames();
        if (sourceNames == null || sourceNames.isEmpty()) {
            return true;
        }
        for (String one : sourceNames) {
            if (StringUtils.hasText(one) && sourceName.equalsIgnoreCase(one.trim())) {
                return true;
            }
        }
        return false;
    }

    private static File resolveLocalJsonFile(String path) {
        if (!StringUtils.hasText(path)) {
            return new File("");
        }
        File f = new File(path);
        if (f.isAbsolute()) {
            return f;
        }
        return f.getAbsoluteFile();
    }

    private static String buildLocalSogouUrl(String sourceName, String filePath, String title, String content) {
        String normalizedSource = normalizeText(sourceName).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (!StringUtils.hasText(normalizedSource)) {
            normalizedSource = "sogou";
        }
        String hash = HashUtil.sha256Hex(normalizeText(filePath) + "\n" + normalizeText(title) + "\n" + normalizeText(content));
        return LOCAL_SOGOU_URL_PREFIX + normalizedSource + "/" + hash;
    }

    private ListFetchOutcome fetchListUrlsFromOne(CrawlerProperties.SourceProperties source, String listUrl) {
        try {
            FetchResult listResp = fetchHtmlPreferPlaywrightForWeixin(listUrl, source.getBaseUrl());
            if (listResp.getStatus() >= 400) {
                if (listResp.getStatus() == 521) {
                    log.warn("List fetch blocked (521). source={}, url={}", source.getName(), listUrl);
                }
                log.warn("List fetch failed: status={}, url={}", listResp.getStatus(), listUrl);
                return ListFetchOutcome.failed();
            }
            String body = listResp.getBody() == null ? "" : listResp.getBody();
            boolean looksLikeXml = listUrl != null && listUrl.toLowerCase().contains(".xml");
            if (!looksLikeXml) {
                String ct = listResp.getContentType();
                looksLikeXml = ct != null && ct.toLowerCase().contains("xml");
            }
            if (!looksLikeXml) {
                String trimmed = body.trim();
                looksLikeXml = trimmed.startsWith("<?xml") || trimmed.startsWith("<rss") || trimmed.startsWith("<feed");
            }

            if (looksLikeXml) {
                Document doc = Jsoup.parse(body, source.getBaseUrl(), Parser.xmlParser());
                List<String> urls = new ArrayList<>();
                for (Element link : doc.select("item > link")) {
                    String href = link.text();
                    if (StringUtils.hasText(href) && href.startsWith("http")) {
                        urls.add(href.trim());
                    }
                }
                if (!urls.isEmpty()) {
                    List<String> shtml = new ArrayList<>();
                    for (String u : urls) {
                        if (u.toLowerCase().contains(".shtml")) {
                            shtml.add(u);
                        }
                    }
                    if (!shtml.isEmpty()) {
                        return ListFetchOutcome.success(shtml);
                    }
                }
                if (!urls.isEmpty()) {
                    return ListFetchOutcome.success(urls);
                }
                for (Element link : doc.select("entry > link[href]")) {
                    String href = link.attr("href");
                    if (StringUtils.hasText(href) && href.startsWith("http")) {
                        urls.add(href.trim());
                    }
                }
                return ListFetchOutcome.success(urls);
            }

            Document doc = Jsoup.parse(body, source.getBaseUrl());
            List<String> strategyUrls = extractBySiteStrategy(source, doc, body);
            if (!strategyUrls.isEmpty()) {
                return ListFetchOutcome.success(strategyUrls);
            }
            return ListFetchOutcome.success(extractGenericListUrls(source, doc));
        } catch (Exception e) {
            log.warn("List fetch/parse error for source: {}, url={}", source.getName(), listUrl, e);
            return ListFetchOutcome.failed();
        }
    }

    private List<String> extractBySiteStrategy(CrawlerProperties.SourceProperties source, Document doc, String body) {
        String strategy = source == null ? "" : safe(source.getStrategy()).toLowerCase();
        if (!StringUtils.hasText(strategy)) {
            strategy = "generic";
        }
        if ("gjfzpt".equals(strategy) || "anti-fraud-center".equals(strategy)) {
            return extractByStrategySelectors(doc, "a[href*=/news], a[href*=article], a[href*=detail], a[href*=fz], .news-list a[href]");
        }
        if ("sina-fanzha".equals(strategy) || "sina-topic".equals(strategy)) {
            List<String> fromDoc = extractByStrategySelectors(doc, "a[href*='sina.cn'], a[href*='sina.com.cn'], a[href*='/202'], a[href*='/detail'], .news-item a[href]");
            if (!fromDoc.isEmpty()) {
                return fromDoc;
            }
            return extractHttpUrlsFromBody(body, "sina.cn");
        }
        if ("360-liewang".equals(strategy) || "liewang".equals(strategy)) {
            List<String> fromDoc = extractByStrategySelectors(doc, "a[href*='/fz/'], a[href*='/news/'], a[href*='liewang'], .list a[href], .article a[href]");
            if (!fromDoc.isEmpty()) {
                return fromDoc;
            }
            return extractHttpUrlsFromBody(body, "360.cn");
        }
        if ("sogou-weixin".equals(strategy) || "sogou_weixin".equals(strategy)) {
            List<String> fromDoc = extractSogouWeixinArticleLinks(doc);
            if (!fromDoc.isEmpty()) {
                return fromDoc;
            }
            return extractHttpUrlsFromBody(body, "weixin.sogou.com/link");
        }
        if ("baidu-fqz".equals(strategy) || "baidu_fqz".equals(strategy)) {
            List<String> fromDoc = extractBaiduFqzListUrls(doc);
            if (!fromDoc.isEmpty()) {
                return fromDoc;
            }
            List<String> fromBody = extractBaiduFqzListUrlsFromBody(body);
            if (!fromBody.isEmpty()) {
                return fromBody;
            }
            return extractHttpUrlsFromBody(body, "110.baidu.com");
        }
        return Collections.emptyList();
    }

    private static List<String> extractSogouWeixinArticleLinks(Document doc) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (Element a : doc.select("a[href*='weixin.sogou.com/link']")) {
            String href = a.absUrl("href");
            if (!StringUtils.hasText(href)) {
                href = a.attr("href");
            }
            if (!isSogouWeixinArticleLink(href)) continue;
            ordered.add(href.trim());
            if (ordered.size() >= 80) {
                break;
            }
        }
        if (!ordered.isEmpty()) {
            return new ArrayList<>(ordered);
        }
        for (Element a : doc.select("div.txt-box h3 a[href], div.news-box a[href], li.res-list a[href]")) {
            String href = a.absUrl("href");
            if (!isSogouWeixinArticleLink(href)) continue;
            ordered.add(href.trim());
            if (ordered.size() >= 80) {
                break;
            }
        }
        return new ArrayList<>(ordered);
    }

    private static boolean isSogouWeixinArticleLink(String href) {
        if (!StringUtils.hasText(href) || !href.startsWith("http")) {
            return false;
        }
        String lower = href.toLowerCase();
        if (!lower.contains("weixin.sogou.com/link")) {
            return false;
        }
        return lower.contains("type=2") || lower.contains("type%3d2");
    }

    private static List<String> extractBaiduFqzListUrls(Document doc) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String[] selectors = new String[]{
                "a[href*='110.baidu.com']",
                "a[href*='baidu.com/fqz']",
                "a[href*='/fqz/']",
                ".news-item a[href], .list-item a[href], .article-list a[href], li a[href]"
        };
        for (String sel : selectors) {
            for (Element a : doc.select(sel)) {
                String href = a.absUrl("href");
                if (!StringUtils.hasText(href)) {
                    href = a.attr("href");
                }
                if (!href.startsWith("http")) continue;
                if (!isLikelyArticleUrl(href)) continue;
                String lower = href.toLowerCase();
                if (!lower.contains("baidu.com")) continue;
                if (lower.contains("passport.baidu.com") || lower.contains("wappass.baidu.com")) continue;
                ordered.add(href.trim());
                if (ordered.size() >= 80) {
                    return new ArrayList<>(ordered);
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    private static List<String> extractBaiduFqzListUrlsFromBody(String body) {
        if (!StringUtils.hasText(body)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        Pattern full = Pattern.compile("https?://110\\.baidu\\.com/fqz/news/[\\w\\-]+", Pattern.CASE_INSENSITIVE);
        Matcher m1 = full.matcher(body);
        while (m1.find()) {
            String u = normalizeUrl(m1.group());
            if (isLikelyArticleUrl(u)) {
                urls.add(u);
            }
            if (urls.size() >= 80) {
                return new ArrayList<>(urls);
            }
        }

        Pattern relative = Pattern.compile("\"(/fqz/news/[\\w\\-]+)\"");
        Matcher m2 = relative.matcher(body);
        while (m2.find()) {
            String u = "https://110.baidu.com" + m2.group(1);
            u = normalizeUrl(u);
            if (isLikelyArticleUrl(u)) {
                urls.add(u);
            }
            if (urls.size() >= 80) {
                return new ArrayList<>(urls);
            }
        }
        return new ArrayList<>(urls);
    }

    private List<String> extractByStrategySelectors(Document doc, String selector) {
        Elements links = doc.select(selector);
        List<String> urls = new ArrayList<>();
        for (Element a : links) {
            String href = a.absUrl("href");
            if (!StringUtils.hasText(href)) {
                href = a.attr("href");
            }
            if (!StringUtils.hasText(href)) continue;
            if (!href.startsWith("http")) continue;
            if (!isLikelyArticleUrl(href)) continue;
            urls.add(href.trim());
        }
        return urls;
    }

    private List<String> extractHttpUrlsFromBody(String body, String mustContainDomain) {
        if (!StringUtils.hasText(body)) {
            return Collections.emptyList();
        }
        Pattern pattern = Pattern.compile("https?://[\\w\\-./?%&=:#]+");
        Matcher matcher = pattern.matcher(body);
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        while (matcher.find()) {
            String url = matcher.group();
            if (!StringUtils.hasText(url)) continue;
            if (StringUtils.hasText(mustContainDomain) && !url.toLowerCase().contains(mustContainDomain.toLowerCase())) continue;
            if (!isLikelyArticleUrl(url)) continue;
            urls.add(url);
            if (urls.size() >= 200) {
                break;
            }
        }
        return new ArrayList<>(urls);
    }

    private List<String> extractGenericListUrls(CrawlerProperties.SourceProperties source, Document doc) {
        String listSel = StringUtils.hasText(source.getListLinkSelector()) ? normalizeCssSelector(source.getListLinkSelector()) : "a[href]";
        Elements links = safeSelect(doc, listSel);
        List<String> urls = new ArrayList<>();
        for (Element a : links) {
            String href = a.absUrl("href");
            if (!StringUtils.hasText(href)) {
                href = a.attr("href");
            }
            if (!StringUtils.hasText(href)) continue;
            if (!href.startsWith("http")) continue;
            if (!isLikelyArticleUrl(href)) continue;
            urls.add(href);
        }
        return urls;
    }

    /**
     * 搜狗 / 微信正文优先尝试无头浏览器抓取；失败后回退 OkHttp。
     */
    private FetchResult fetchHtmlPreferPlaywrightForWeixin(String url, String referer) throws Exception {
        if (shouldPreferBrowser(url) && playwrightArticleFetcher != null) {
            FetchResult browserResp = playwrightArticleFetcher.fetch(url, referer);
            if (browserResp != null && browserResp.getStatus() < 400 && StringUtils.hasText(browserResp.getBody())) {
                if (htmlLooksUseful(browserResp.getBody(), browserResp.getFinalUrl())) {
                    return browserResp;
                }
                log.warn("Browser fetched but body still looks anti-bot/empty, fallback to OkHttp. url={}", abbreviateUrl(url));
            }
        }
        return fetchClient.get(url, referer);
    }

    private static boolean shouldPreferBrowser(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String u = url.toLowerCase();
        return u.contains("weixin.sogou.com")
                || u.contains("mp.weixin.qq.com")
                || u.contains("110.baidu.com/fqz");
    }

    private static boolean htmlLooksUseful(String body, String finalUrl) {
        if (!StringUtils.hasText(body)) {
            return false;
        }
        String b = body.toLowerCase();
        if (b.contains("antispider") || b.contains("请输入验证码") || b.contains("secimg")) {
            return false;
        }
        String f = finalUrl == null ? "" : finalUrl.toLowerCase();
        if (f.contains("mp.weixin.qq.com")) {
            return b.contains("js_content") && body.replaceAll("\\s+", "").length() > 500;
        }
        return body.length() > 1000;
    }

    private static String abbreviateUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        return url.length() <= 120 ? url : url.substring(0, 120) + "...";
    }

    /**
     * 跳过规则（SKIPPED）：来源无法匹配；URL 已在 checkpoint；发布时间超出窗口且不允许未知时间；
     * 正文过短；关键词判定不通过（{@link #containsFraudKeyword}）；灰度相关分低于阈值；
     * 内容哈希或（非空）标题指纹重复；数据库 upsert 判定为重复。
     * 失败（FAILED）：HTTP 4xx+、向量生成失败等。
     */
    private ProcessResult processOne(String url, List<CrawlerProperties.SourceProperties> sources, CrawlerCheckpoint checkpoint) {
        if (isLocalSogouUrl(url)) {
            CrawlerProperties.SourceProperties localSource = matchSourceForLocalSogouUrl(url, sources);
            if (localSource == null) {
                log.warn("Skip local sogou article: source not matched. url={}", url);
                return ProcessResult.SKIPPED;
            }
            return processLocalSogouArticle(url, localSource, checkpoint);
        }
        CrawlerProperties.SourceProperties source = matchSource(url, sources);
        if (source == null) {
            log.info("Skip article: source not matched url={}", url);
            return ProcessResult.SKIPPED;
        }

        try {
            String referer = resolveListUrls(source).isEmpty() ? source.getBaseUrl() : resolveListUrls(source).get(0);
            FetchResult articleResp = fetchHtmlPreferPlaywrightForWeixin(url, referer);
            if (articleResp.getStatus() >= 400) {
                log.warn("Article fetch failed: status={}, url={}", articleResp.getStatus(), url);
                return ProcessResult.FAILED;
            }

            Document doc = Jsoup.parse(articleResp.getBody(), source.getBaseUrl());
            String finalUrl = normalizeUrl(StringUtils.hasText(articleResp.getFinalUrl()) ? articleResp.getFinalUrl() : url);
            if (!StringUtils.hasText(finalUrl)) {
                finalUrl = url;
            }
            if (isAntiSpiderUrl(finalUrl)) {
                // Sogou anti-spider pages collapse many links into one URL; do not mark as seen.
                SogouListFallback fallback = fetchSogouFallbackFromList(referer, url, source);
                if (fallback != null) {
                    log.warn("Sogou anti-spider hit; fallback to list snippet url={} referer={}", url, referer);
                    return processFallbackSnippet(
                            url,
                            fallback.getTitle(),
                            fallback.getContent(),
                            fallback.getRawHtml(),
                            source,
                            checkpoint
                    );
                }
                if (!crawlerProperties.isSogouPlaceholderEnabled()) {
                    // 不标记 seen：手动过验证码后允许同一链接在后续批次重试抓取真实正文
                    markSourceFailure(source, checkpoint);
                    log.warn("Sogou anti-spider hit and no fallback snippet found; placeholder disabled, skip for now (will retry in next batches) url={} referer={}", url, referer);
                    return ProcessResult.SKIPPED;
                }
                // 可选兜底：占位记录
                log.warn("Sogou anti-spider hit and no fallback snippet found. use placeholder. url={} referer={}", url, referer);
                SogouListFallback placeholder = buildAntiSpiderPlaceholder(url, articleResp.getBody());
                return processFallbackSnippet(
                        url,
                        placeholder.getTitle(),
                        placeholder.getContent(),
                        placeholder.getRawHtml(),
                        source,
                        checkpoint
                );
            }
            if (checkpoint.getSeenUrls().contains(finalUrl)) {
                log.info("Skip article: already seen url={} source={}", finalUrl, source.getName());
                return ProcessResult.SKIPPED;
            }

            String title = extractTitle(doc, source.getArticleTitleSelector());
            String publishTimeText = extractPublishTimeText(doc, source.getArticleTimeSelector());
            String content = extractArticleContent(doc, source.getArticleContentSelector());
            String rawHtml = articleResp.getBody();
            title = StringUtils.hasText(title) ? title : null;
            content = StringUtils.hasText(content) ? content : null;
            if (!StringUtils.hasText(title) && !StringUtils.hasText(content)) {
                log.warn("Empty title and content after HTML parse (often anti-bot or jump page). requestUrl={} finalUrl={}", url, finalUrl);
                if (isWeixinSogouContext(url, finalUrl) || htmlLooksLikeSogouAntiBot(rawHtml)) {
                    SogouListFallback listFb = fetchSogouFallbackFromList(referer, url, source);
                    if (listFb != null && StringUtils.hasText(listFb.getContent())) {
                        return processFallbackSnippet(
                                url,
                                listFb.getTitle(),
                                listFb.getContent(),
                                listFb.getRawHtml(),
                                source,
                                checkpoint
                        );
                    }
                    SogouListFallback ph = buildAntiSpiderPlaceholder(url, rawHtml);
                    return processFallbackSnippet(
                            url,
                            ph.getTitle(),
                            ph.getContent(),
                            ph.getRawHtml(),
                            source,
                            checkpoint
                    );
                }
                SogouListFallback generic = buildGenericEmptyParsePlaceholder(url, finalUrl, rawHtml);
                return processFallbackSnippet(
                        url,
                        generic.getTitle(),
                        generic.getContent(),
                        generic.getRawHtml(),
                        source,
                        checkpoint
                );
            }
            LocalDateTime publishTime = parseTime(publishTimeText, source.getPublishTimeFormat());
            RelevanceScore relevance = evaluateRelevance(title, content, publishTime, source);
            if (!isWithinRecentRange(publishTime, crawlerProperties.getMaxArticleAgeDays(), crawlerProperties.isAllowUnknownPublishTime())) {
                checkpoint.getSeenUrls().add(finalUrl);
                log.info("Skip article: out of date url={} publishTime={} source={}", finalUrl, publishTimeText, source.getName());
                return ProcessResult.SKIPPED;
            }
            int contentLen = content == null ? 0 : content.length();
            if (contentLen < MIN_ARTICLE_CONTENT_LENGTH
                    && !StringUtils.hasText(rawHtml)
                    && relevance.getFinalScore() < RELAXED_RELEVANCE_FLOOR) {
                checkpoint.getSeenUrls().add(finalUrl);
                log.info("Skip article: too short and low relevance url={} len={} score={}",
                        finalUrl, contentLen, String.format("%.3f", relevance.getFinalScore()));
                return ProcessResult.SKIPPED;
            }
            boolean keywordHit = containsFraudKeyword(title, content, crawlerProperties.getFraudKeywords());
            if (crawlerProperties.isRequireKeywordMatch()
                    && !keywordHit
                    && StringUtils.hasText(content)
                    && relevance.getFinalScore() < RELAXED_RELEVANCE_FLOOR) {
                checkpoint.getSeenUrls().add(finalUrl);
                log.info("Skip article: keyword miss and low relevance url={} score={}",
                        finalUrl, String.format("%.3f", relevance.getFinalScore()));
                return ProcessResult.SKIPPED;
            }
            double minScore = crawlerProperties.getMinFraudScore() > 0 ? crawlerProperties.getMinFraudScore() : DEFAULT_MIN_FRAUD_SCORE;
            if (crawlerProperties.isGrayEnhancedFilterEnabled() && relevance.getFinalScore() < minScore) {
                checkpoint.getSeenUrls().add(finalUrl);
                log.info("Skip article: gray filter url={} score={} threshold={}",
                        finalUrl, String.format("%.3f", relevance.getFinalScore()), String.format("%.3f", minScore));
                return ProcessResult.SKIPPED;
            }

            String titleFingerprint = HashUtil.sha256Hex(normalizeTitleForFingerprint(title));
            if (hasDistinctTitleFingerprint(title)
                    && checkpoint.getSeenTitleFingerprints().contains(titleFingerprint)) {
                checkpoint.getSeenUrls().add(finalUrl);
                log.info("Skip article: duplicate title fingerprint url={}", finalUrl);
                return ProcessResult.SKIPPED;
            }

            String summary = summarize(content);
            String tagText = String.join(",", relevance.getTags());

            FraudCaseExtraction extraction = null;
            try {
                extraction = extractor.extract(title, content);
                if (extraction != null) {
                    log.info("DeepSeek extract success. url={}", finalUrl);
                } else {
                    log.warn("DeepSeek extract returned empty result. url={}", finalUrl);
                }
            } catch (Exception e) {
                log.warn("DeepSeek extract failed, fallback to raw text embedding. url={}, err={}", finalUrl, e.getMessage());
            }
            String deepseekJson = null;
            if (extraction != null) {
                try {
                    deepseekJson = objectMapper.writeValueAsString(extraction);
                } catch (Exception e) {
                    log.warn("DeepSeek result serialization failed. url={}, err={}", finalUrl, e.getMessage());
                }
            }
            if (!StringUtils.hasText(deepseekJson)) {
                deepseekJson = buildFallbackDeepseekJson(title, content, source == null ? null : source.getName());
            }

            String dbUrl = truncateUrlForDb(finalUrl);
            String contentHash = HashUtil.sha256Hex(dbUrl + "\n" + (title == null ? "" : title) + "\n" + (content == null ? "" : content));
            boolean saved = fraudNewsStore.upsert(new FraudNewsRow(
                    dbUrl,
                    title,
                    summary,
                    content,
                    publishTime,
                    source.getName(),
                    tagText,
                    relevance.getFinalScore(),
                    rawHtml,
                    contentHash,
                    deepseekJson
            ));
            if (!saved) {
                checkpoint.getSeenUrls().add(finalUrl);
                if (hasDistinctTitleFingerprint(title)) {
                    checkpoint.getSeenTitleFingerprints().add(titleFingerprint);
                }
                trimSeen(checkpoint);
                log.info("Skip article: upsert returned false (duplicate likely) url={} hash={}", finalUrl, contentHash);
                return ProcessResult.SKIPPED;
            }

            String methodText = extraction != null && extraction.getFraudMethod() != null ? safe(extraction.getFraudMethod().getScript()) : "";
            String victimText = extraction != null && extraction.getVictimGroup() != null ? safe(extraction.getVictimGroup().getAgeRange()) + " " + safe(extraction.getVictimGroup().getOccupation()) + " " + safe(extraction.getVictimGroup().getRegionTag()) : "";

            String embedText = (methodText + "\n" + victimText).trim();
            if (!StringUtils.hasText(embedText)) {
                embedText = (title + "\n" + content);
                if (embedText.length() > 4000) {
                    embedText = embedText.substring(0, 4000);
                }
            }

            try {
                List<Float> vector;
                try {
                    vector = embeddingClient.embedSingle(embedText);
                } catch (Exception e) {
                    log.warn("Embedding failed, fallback to hash vector. url={}, err={}", finalUrl, e.getMessage());
                    vector = hashVector(embedText, embeddingDim);
                }
                if (vector == null || vector.isEmpty()) {
                    throw new IllegalStateException("empty embedding vector");
                }

                Map<String, Object> meta = new HashMap<>();
                meta.put("url", dbUrl);
                meta.put("title", title);
                meta.put("source", source.getName());
                meta.put("publish_time", publishTimeText);
                meta.put("content_hash", contentHash);
                meta.put("summary", summary);
                meta.put("fraud_tags", tagText);
                meta.put("confidence", relevance.getFinalScore());
                meta.put("has_deepseek_analysis", extraction != null);
                if (StringUtils.hasText(methodText) && methodText.length() > 500) {
                    meta.put("fraud_method_preview", methodText.substring(0, 500));
                } else if (StringUtils.hasText(methodText)) {
                    meta.put("fraud_method_preview", methodText);
                }

                chromaService.upsert(
                        chromaCollection,
                        Collections.singletonList(dbUrl),
                        Collections.singletonList(vector),
                        Collections.singletonList(embedText),
                        Collections.singletonList(meta)
                );
            } catch (Exception e) {
                log.warn("Vector/Chroma step failed after MySQL persist; article remains in DB. url={}, err={}", finalUrl, e.getMessage());
            }

            checkpoint.getSeenUrls().add(finalUrl);
            if (hasDistinctTitleFingerprint(title)) {
                checkpoint.getSeenTitleFingerprints().add(titleFingerprint);
            }
            markSourceSuccess(source, checkpoint);
            trimSeen(checkpoint);
            log.info("Stored article (MySQL): url={}", finalUrl);
            return ProcessResult.STORED;
        } catch (Exception e) {
            log.warn("Process article failed: url={}", url, e);
            return ProcessResult.FAILED;
        }
    }

    private ProcessResult processLocalSogouArticle(String localUrl, CrawlerProperties.SourceProperties source, CrawlerCheckpoint checkpoint) {
        LocalSogouArticle article = localSogouArticleIndex.get(localUrl);
        if (article == null) {
            log.warn("Local sogou article missing in memory index, skip. url={}", localUrl);
            return ProcessResult.SKIPPED;
        }
        String rawHtml = "LOCAL_SOGOU_JSON"
                + " website_name=" + safe(article.getWebsiteName())
                + " file_path=" + safe(article.getFilePath());
        return processFallbackSnippet(
                article.getSyntheticUrl(),
                article.getTitle(),
                article.getContent(),
                rawHtml,
                source,
                checkpoint
        );
    }

    private ProcessResult processFallbackSnippet(
            String originalUrl,
            String title,
            String content,
            String rawHtml,
            CrawlerProperties.SourceProperties source,
            CrawlerCheckpoint checkpoint
    ) {
        try {
            String sourceName = source == null ? "unknown" : source.getName();
            String finalUrl = normalizeUrl(originalUrl);
            if (!StringUtils.hasText(finalUrl)) {
                finalUrl = originalUrl;
            }
            String dbUrl = truncateUrlForDb(finalUrl);
            if (checkpoint.getSeenUrls().contains(finalUrl)) {
                return ProcessResult.SKIPPED;
            }
            if (!StringUtils.hasText(content)) {
                return ProcessResult.SKIPPED;
            }
            RelevanceScore relevance = evaluateRelevance(title, content, null, source);
            if (relevance.getFinalScore() < RELAXED_RELEVANCE_FLOOR
                    && !containsFraudKeyword(title, content, crawlerProperties.getFraudKeywords())) {
                checkpoint.getSeenUrls().add(finalUrl);
                return ProcessResult.SKIPPED;
            }

            String contentHash = HashUtil.sha256Hex(dbUrl + "\n" + (title == null ? "" : title) + "\n" + content);
            String summary = summarize(content);
            String deepseekJson = null;
            FraudCaseExtraction extraction = null;
            try {
                extraction = extractor.extract(title, content);
                if (extraction != null) {
                    deepseekJson = objectMapper.writeValueAsString(extraction);
                    log.info("DeepSeek extract success for fallback snippet. url={}", finalUrl);
                } else {
                    log.warn("DeepSeek extract returned empty for fallback snippet. url={}", finalUrl);
                }
            } catch (Exception e) {
                log.warn("DeepSeek extract failed for fallback snippet. url={}, err={}", finalUrl, e.getMessage());
            }
            if (!StringUtils.hasText(deepseekJson)) {
                deepseekJson = buildFallbackDeepseekJson(title, content, sourceName);
            }

            boolean saved = fraudNewsStore.upsert(new FraudNewsRow(
                    dbUrl,
                    title,
                    summary,
                    content,
                    null,
                    sourceName,
                    String.join(",", relevance.getTags()),
                    relevance.getFinalScore(),
                    rawHtml,
                    contentHash,
                    deepseekJson
            ));
            if (!saved) {
                checkpoint.getSeenUrls().add(finalUrl);
                return ProcessResult.SKIPPED;
            }

            // Also push to Chroma so MySQL + vector DB are consistent.
            try {
                String methodText = extraction != null && extraction.getFraudMethod() != null ? safe(extraction.getFraudMethod().getScript()) : "";
                String victimText = extraction != null && extraction.getVictimGroup() != null
                        ? safe(extraction.getVictimGroup().getAgeRange()) + " " + safe(extraction.getVictimGroup().getOccupation()) + " " + safe(extraction.getVictimGroup().getRegionTag())
                        : "";

                String embedText = (methodText + "\n" + victimText).trim();
                if (!StringUtils.hasText(embedText)) {
                    embedText = (title + "\n" + content);
                    if (embedText.length() > 4000) {
                        embedText = embedText.substring(0, 4000);
                    }
                }

                List<Float> vector;
                try {
                    vector = embeddingClient.embedSingle(embedText);
                } catch (Exception e) {
                    log.warn("Embedding failed for fallback snippet, fallback to hash vector. url={}, err={}", finalUrl, e.getMessage());
                    vector = hashVector(embedText, embeddingDim);
                }
                if (vector == null || vector.isEmpty()) {
                    throw new IllegalStateException("empty embedding vector");
                }

                Map<String, Object> meta = new HashMap<>();
                meta.put("url", dbUrl);
                meta.put("title", title);
                meta.put("source", sourceName);
                meta.put("publish_time", "");
                meta.put("content_hash", contentHash);
                meta.put("summary", summary);
                meta.put("fraud_tags", String.join(",", relevance.getTags()));
                meta.put("confidence", relevance.getFinalScore());
                meta.put("has_deepseek_analysis", extraction != null);

                chromaService.upsert(
                        chromaCollection,
                        Collections.singletonList(dbUrl),
                        Collections.singletonList(vector),
                        Collections.singletonList(embedText),
                        Collections.singletonList(meta)
                );
                log.info("Stored fallback snippet to Chroma. url={}", finalUrl);
            } catch (Exception e) {
                log.warn("Chroma step failed for fallback snippet; MySQL已入库仍继续。url={}, err={}", finalUrl, e.getMessage());
            }

            String titleFingerprint = HashUtil.sha256Hex(normalizeTitleForFingerprint(title));
            checkpoint.getSeenUrls().add(finalUrl);
            if (hasDistinctTitleFingerprint(title)) {
                checkpoint.getSeenTitleFingerprints().add(titleFingerprint);
            }
            markSourceSuccess(source, checkpoint);
            trimSeen(checkpoint);
            log.info("Stored fallback snippet (MySQL+Chroma): url={}", finalUrl);
            return ProcessResult.STORED;
        } catch (Exception e) {
            log.warn("Fallback snippet process failed: url={}", originalUrl, e);
            return ProcessResult.FAILED;
        }
    }

    private SogouListFallback fetchSogouFallbackFromList(String listUrl, String targetLink, CrawlerProperties.SourceProperties source) {
        try {
            if (!StringUtils.hasText(listUrl) || !StringUtils.hasText(targetLink)) {
                return null;
            }
            FetchResult listResp = fetchHtmlPreferPlaywrightForWeixin(listUrl, source == null ? null : source.getBaseUrl());
            if (listResp.getStatus() >= 400 || !StringUtils.hasText(listResp.getBody())) {
                return null;
            }
            Document listDoc = Jsoup.parse(listResp.getBody(), source == null ? null : source.getBaseUrl());
            String targetToken = normalizeSogouToken(queryParam(targetLink, "url"));
            SogouListFallback bestEffort = null;
            for (Element a : listDoc.select("a[href*='weixin.sogou.com/link']")) {
                String href = a.absUrl("href");
                if (!StringUtils.hasText(href)) {
                    href = a.attr("href");
                }
                if (!StringUtils.hasText(href)) {
                    continue;
                }
                String title = normalizeText(a.text());
                String content = extractSogouSnippet(a, title);
                if (!StringUtils.hasText(content)) {
                    continue;
                }

                if (bestEffort == null) {
                    bestEffort = new SogouListFallback(title, content, listResp.getBody());
                }

                boolean matched;
                if (StringUtils.hasText(targetToken)) {
                    String hrefToken = normalizeSogouToken(queryParam(href, "url"));
                    matched = isSameSogouToken(targetToken, hrefToken);
                } else {
                    matched = normalizeUrl(href).equals(normalizeUrl(targetLink));
                }
                if (matched) {
                    return new SogouListFallback(title, content, listResp.getBody());
                }
            }
            // 如果精确匹配失败，返回列表中的首条有效结果（避免占位文本）
            if (bestEffort != null) {
                log.warn("Sogou fallback exact link match missed; use first valid list item. target={}", targetLink);
            }
            return bestEffort;
        } catch (Exception e) {
            log.debug("fetchSogouFallbackFromList failed: {}", e.getMessage());
            return null;
        }
    }

    private static String extractSogouSnippet(Element a, String title) {
        if (a == null) {
            return "";
        }
        // 优先取常见摘要节点
        Element row = a.closest("li");
        if (row == null) row = a.closest(".news-box");
        if (row == null) row = a.closest(".txt-box");
        if (row == null) row = a.parent();

        String snippet = "";
        if (row != null) {
            Element p = row.selectFirst(".txt-info, p.txt-info, .s-p, p");
            if (p != null) {
                snippet = normalizeText(p.text());
            }
            if (!StringUtils.hasText(snippet)) {
                snippet = normalizeText(row.text());
            }
        }
        if (!StringUtils.hasText(snippet)) {
            snippet = normalizeText(a.attr("title"));
        }
        if (!StringUtils.hasText(snippet)) {
            snippet = normalizeText(a.text());
        }
        if (StringUtils.hasText(title) && StringUtils.hasText(snippet) && snippet.startsWith(title)) {
            snippet = normalizeText(snippet.substring(title.length()));
        }
        return snippet;
    }

    private static String queryParam(String url, String key) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(key)) {
            return "";
        }
        try {
            int q = url.indexOf('?');
            if (q < 0 || q >= url.length() - 1) {
                return "";
            }
            String query = url.substring(q + 1);
            String[] parts = query.split("&");
            for (String p : parts) {
                int eq = p.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = p.substring(0, eq);
                if (key.equalsIgnoreCase(k)) {
                    return p.substring(eq + 1);
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isWeixinSogouContext(String requestUrl, String finalUrl) {
        String a = requestUrl == null ? "" : requestUrl.toLowerCase();
        String b = finalUrl == null ? "" : finalUrl.toLowerCase();
        return a.contains("weixin.sogou.com") || b.contains("weixin.sogou.com");
    }

    private static boolean htmlLooksLikeSogouAntiBot(String rawHtml) {
        if (!StringUtils.hasText(rawHtml)) {
            return false;
        }
        String h = rawHtml.toLowerCase();
        return h.contains("antispider")
                || h.contains("请输入验证码")
                || h.contains("验证码")
                || h.contains("snuid")
                || h.contains("secimg")
                || h.contains("/link?url=") && h.contains("weixin.sogou.com");
    }

    /**
     * 非搜狗域名的空解析占位（文案含诈骗相关词，便于通过关键词与 relevance 校验入库）。
     */
    private static SogouListFallback buildGenericEmptyParsePlaceholder(String requestUrl, String finalUrl, String rawHtml) {
        String t = "反诈资讯抓取-页面未解析出正文";
        StringBuilder c = new StringBuilder();
        c.append("未能从该页解析出标题与正文，常见于反爬验证、需 Cookie、前端渲染或选择器失效。");
        c.append(" 本条为占位记录，便于追溯；诈骗相关正文未从 HTML 提取成功。");
        if (StringUtils.hasText(requestUrl)) {
            c.append(" 请求URL：").append(requestUrl);
        }
        if (StringUtils.hasText(finalUrl) && !finalUrl.equals(requestUrl)) {
            c.append(" 落地URL：").append(finalUrl);
        }
        c.append(" 建议在 application.yml 的 crawler.extra-headers 注入浏览器 Cookie，或启用代理/无头浏览器后再抓搜狗微信落地页。");
        return new SogouListFallback(t, c.toString(), rawHtml);
    }

    private static SogouListFallback buildAntiSpiderPlaceholder(String targetLink, String rawHtml) {
        String q = decodeQueryForDisplay(queryParam(targetLink, "query"));
        String token = queryParam(targetLink, "url");
        String shortToken = StringUtils.hasText(token) ? token.substring(0, Math.min(32, token.length())) : "";
        String title = StringUtils.hasText(q) ? ("搜狗反爬占位-" + q) : "搜狗反爬占位";
        StringBuilder content = new StringBuilder();
        content.append("该条目命中搜狗反爬页面，未获取到原文正文。");
        if (StringUtils.hasText(q)) {
            content.append(" 检索词：").append(q).append("。");
        }
        if (StringUtils.hasText(shortToken)) {
            content.append(" 链接标识：").append(shortToken).append("...");
        }
        content.append(" 原始链接：").append(targetLink);
        return new SogouListFallback(title, content.toString(), rawHtml);
    }

    private static String decodeQueryForDisplay(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String normalizeSogouToken(String token) {
        String t = decodeQueryForDisplay(token);
        t = decodeQueryForDisplay(t);
        if (!StringUtils.hasText(t)) {
            return "";
        }
        return t.trim().replace(" ", "");
    }

    private static boolean isSameSogouToken(String a, String b) {
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int n = Math.min(Math.min(a.length(), b.length()), 20);
        if (n < 8) {
            return false;
        }
        return a.substring(0, n).equals(b.substring(0, n));
    }

    private static boolean isSogouSource(CrawlerProperties.SourceProperties source) {
        if (source == null) {
            return false;
        }
        String strategy = source.getStrategy() == null ? "" : source.getStrategy().trim().toLowerCase();
        String baseUrl = source.getBaseUrl() == null ? "" : source.getBaseUrl().trim().toLowerCase();
        return strategy.contains("sogou") || baseUrl.contains("weixin.sogou.com");
    }

    private static boolean isLocalSogouUrl(String url) {
        return StringUtils.hasText(url) && url.startsWith(LOCAL_SOGOU_URL_PREFIX);
    }

    private static boolean isAntiSpiderUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("weixin.sogou.com/antispider");
    }

    private static class SogouListFallback {
        private final String title;
        private final String content;
        private final String rawHtml;

        private SogouListFallback(String title, String content, String rawHtml) {
            this.title = title;
            this.content = content;
            this.rawHtml = rawHtml;
        }

        private String getTitle() {
            return title;
        }

        private String getContent() {
            return content;
        }

        private String getRawHtml() {
            return rawHtml;
        }
    }

    private static class LocalSogouArticle {
        private final String syntheticUrl;
        private final String title;
        private final String content;
        private final String websiteName;
        private final String filePath;

        private LocalSogouArticle(String syntheticUrl, String title, String content, String websiteName, String filePath) {
            this.syntheticUrl = syntheticUrl;
            this.title = title;
            this.content = content;
            this.websiteName = websiteName;
            this.filePath = filePath;
        }

        private String getSyntheticUrl() {
            return syntheticUrl;
        }

        private String getTitle() {
            return title;
        }

        private String getContent() {
            return content;
        }

        private String getWebsiteName() {
            return websiteName;
        }

        private String getFilePath() {
            return filePath;
        }
    }

    private static class LocalSogouSourceCache {
        private final String signature;
        private final List<String> urls;

        private LocalSogouSourceCache(String signature, List<String> urls) {
            this.signature = signature;
            this.urls = urls == null ? Collections.emptyList() : new ArrayList<>(urls);
        }

        private String getSignature() {
            return signature;
        }

        private List<String> getUrls() {
            return urls;
        }
    }

    private static List<Float> hashVector(String text, int dim) {
        int d = dim > 0 ? dim : 1024;
        byte[] seedBytes;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            seedBytes = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            seedBytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        }

        long seed = 0L;
        if (seedBytes.length >= 8) {
            seed = ByteBuffer.wrap(seedBytes, 0, 8).getLong();
        } else {
            for (byte b : seedBytes) {
                seed = (seed << 8) ^ (b & 0xffL);
            }
        }

        Random random = new Random(seed);
        List<Float> vector = new ArrayList<>(d);
        double sumSq = 0.0d;
        for (int i = 0; i < d; i++) {
            float v = (float) (random.nextDouble() * 2.0d - 1.0d);
            vector.add(v);
            sumSq += (double) v * (double) v;
        }

        double norm = Math.sqrt(sumSq);
        if (norm > 0.0d) {
            for (int i = 0; i < vector.size(); i++) {
                vector.set(i, (float) (vector.get(i) / norm));
            }
        }

        return vector;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static LocalDateTime parseTime(String text, String fmt) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String raw = text.trim();
        if (StringUtils.hasText(fmt)) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fmt);
                return LocalDateTime.parse(raw, formatter);
            } catch (Exception ignore) {
            }
        }
        String normalized = raw
                .replace("年", "-")
                .replace("月", "-")
                .replace("日", " ")
                .replace("/", "-")
                .replace("T", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<String> patterns = Arrays.asList(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-M-d HH:mm:ss",
                "yyyy-M-d HH:mm",
                "yyyy-MM-dd"
        );
        for (String pattern : patterns) {
            try {
                if ("yyyy-MM-dd".equals(pattern)) {
                    return LocalDate.parse(normalized, DateTimeFormatter.ofPattern(pattern)).atStartOfDay();
                }
                return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern(pattern));
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    /**
     * 诈骗相关性（放宽）：命中核心语素（诈/骗等）即可通过；否则需同时命中类型相关词与新闻/警示语境，避免纯泛财经误收。
     */
    static boolean containsFraudKeyword(String title, String content, List<String> configuredKeywords) {
        String normalizedTitle = normalizeText(title).toLowerCase();
        String normalizedContent = normalizeText(content).toLowerCase();
        String normalizedAll = (normalizedTitle + "\n" + normalizedContent).trim();
        if (!StringUtils.hasText(normalizedAll)) {
            return false;
        }

        List<String> typeKeywords = new ArrayList<>(DEFAULT_TYPE_KEYWORDS);
        if (configuredKeywords != null) {
            for (String keyword : configuredKeywords) {
                if (StringUtils.hasText(keyword)) {
                    String normalized = keyword.trim().toLowerCase();
                    if (!typeKeywords.contains(normalized)) {
                        typeKeywords.add(normalized);
                    }
                }
            }
        }

        boolean coreHit = hitsAny(normalizedTitle, DEFAULT_CORE_KEYWORDS) || hitsAny(normalizedContent, DEFAULT_CORE_KEYWORDS);
        boolean typeHit = hitsAny(normalizedTitle, typeKeywords) || hitsAny(normalizedContent, typeKeywords);
        boolean newsHit = hitsAny(normalizedTitle, DEFAULT_NEWS_CONTEXT_KEYWORDS) || hitsAny(normalizedContent, DEFAULT_NEWS_CONTEXT_KEYWORDS);
        return coreHit || (typeHit && newsHit);
    }

    static double semanticSimilarity(String content) {
        String normalized = normalizeText(content).toLowerCase();
        if (!StringUtils.hasText(normalized)) {
            return 0.0d;
        }
        double best = 0.0d;
        Set<String> sourceTerms = splitTerms(normalized);
        for (String prototype : FRAUD_SEMANTIC_PROTOTYPES) {
            Set<String> targetTerms = splitTerms(prototype.toLowerCase());
            double sim = jaccard(sourceTerms, targetTerms);
            if (sim > best) {
                best = sim;
            }
        }
        int fraudHits = keywordHitCount(normalized, DEFAULT_CORE_KEYWORDS) + keywordHitCount(normalized, DEFAULT_TYPE_KEYWORDS);
        double signalBoost = Math.min(0.35d, fraudHits * 0.03d);
        return Math.max(0.0d, Math.min(1.0d, best + signalBoost));
    }

    static double fraudFeatureScore(String title, String content) {
        String source = (normalizeText(title) + " " + normalizeText(content)).toLowerCase();
        if (!StringUtils.hasText(source)) {
            return 0.0d;
        }
        double sumWeight = 0.0d;
        double hitWeight = 0.0d;
        for (Map.Entry<String, Double> entry : FRAUD_FEATURE_WEIGHTS.entrySet()) {
            sumWeight += entry.getValue();
            if (source.contains(entry.getKey())) {
                hitWeight += entry.getValue();
            }
        }
        if (sumWeight <= 0.0d) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, hitWeight / sumWeight));
    }

    private RelevanceScore evaluateRelevance(String title, String content, LocalDateTime publishTime, CrawlerProperties.SourceProperties source) {
        boolean keywordHit = containsFraudKeyword(title, content, crawlerProperties.getFraudKeywords());
        double titleKeywordScore = keywordHit ? 1.0d : 0.45d;
        double semanticScore = semanticSimilarity(content);
        double featureScore = fraudFeatureScore(title, content);
        double freshnessScore = computeFreshnessScore(publishTime, crawlerProperties.getMaxArticleAgeDays(), crawlerProperties.isAllowUnknownPublishTime());
        double sourceScore = clamp01(source == null ? 0.6d : source.getTrustScore());

        double finalScore = titleKeywordScore * 0.20d
                + semanticScore * 0.30d
                + freshnessScore * 0.15d
                + sourceScore * 0.15d
                + featureScore * 0.20d;

        List<String> tags = extractFraudTags(title, content);
        return new RelevanceScore(
                clamp01(titleKeywordScore),
                clamp01(semanticScore),
                clamp01(freshnessScore),
                clamp01(sourceScore),
                clamp01(featureScore),
                clamp01(finalScore),
                tags
        );
    }

    private static double computeFreshnessScore(LocalDateTime publishTime, int maxDays, boolean allowUnknown) {
        if (publishTime == null) {
            return allowUnknown ? 0.65d : 0.0d;
        }
        int days = maxDays > 0 ? maxDays : 90;
        LocalDateTime now = LocalDateTime.now();
        if (publishTime.isAfter(now)) {
            return 0.5d;
        }
        LocalDateTime threshold = now.minusDays(days);
        if (publishTime.isBefore(threshold)) {
            return 0.0d;
        }
        double spanSeconds = Math.max(1.0d, (double) java.time.Duration.between(threshold, now).getSeconds());
        double value = (double) java.time.Duration.between(threshold, publishTime).getSeconds() / spanSeconds;
        return clamp01(value);
    }

    static List<String> extractFraudTags(String title, String content) {
        String source = (normalizeText(title) + " " + normalizeText(content)).toLowerCase();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String type : Arrays.asList("电信诈骗", "网络诈骗", "刷单诈骗", "杀猪盘", "虚假投资", "冒充客服", "冒充公检法", "非法集资", "洗钱")) {
            if (source.contains(type.replace("诈骗", "")) || source.contains(type)) {
                tags.add(type);
            }
        }
        for (String region : Arrays.asList("北京", "上海", "广东", "深圳", "江苏", "浙江", "山东", "四川", "重庆", "武汉", "天津", "河北", "河南", "江西")) {
            if (source.contains(region)) {
                tags.add(region);
            }
        }
        for (String amount : Arrays.asList("万元", "万余元", "千元", "百万", "转账", "充值", "汇款", "损失")) {
            if (source.contains(amount)) {
                tags.add("金额相关");
                break;
            }
        }
        for (String script : Arrays.asList("验证码", "链接", "二维码", "下载app", "客服", "公检法", "账号冻结", "解冻", "返利")) {
            if (source.contains(script)) {
                tags.add("诈骗话术");
                break;
            }
        }
        if (tags.isEmpty()) {
            tags.add("待人工复核");
        }
        return new ArrayList<>(tags);
    }

    private static Map<String, Double> createFraudFeatureWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String k : Arrays.asList("电信诈骗", "网络诈骗", "刷单", "杀猪盘", "冒充客服", "冒充公检法", "虚假投资", "非法集资", "洗钱")) {
            weights.put(k, 0.10d);
        }
        for (String k : Arrays.asList("验证码", "转账", "汇款", "链接", "二维码", "下载app", "冻结", "解冻", "返利")) {
            weights.put(k, 0.06d);
        }
        for (String k : Arrays.asList("万元", "万余元", "百万", "损失", "涉案金额")) {
            weights.put(k, 0.05d);
        }
        for (String k : Arrays.asList("警方", "通报", "破获", "抓获", "预警", "防骗")) {
            weights.put(k, 0.04d);
        }
        return Collections.unmodifiableMap(weights);
    }

    private static Set<String> splitTerms(String text) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = normalizeText(text).toLowerCase();
        if (!StringUtils.hasText(normalized)) {
            return terms;
        }
        for (String word : normalized.split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() >= 2) {
                terms.add(word);
            }
        }
        for (int i = 0; i < normalized.length() - 1; i++) {
            char a = normalized.charAt(i);
            char b = normalized.charAt(i + 1);
            if (Character.isWhitespace(a) || Character.isWhitespace(b)) {
                continue;
            }
            terms.add(String.valueOf(a) + b);
        }
        return terms;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        int inter = 0;
        for (String s : a) {
            if (b.contains(s)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        if (union <= 0) {
            return 0.0d;
        }
        return (double) inter / (double) union;
    }

    private static double clamp01(double v) {
        if (v < 0.0d) {
            return 0.0d;
        }
        if (v > 1.0d) {
            return 1.0d;
        }
        return v;
    }

    private static String summarize(String content) {
        String normalized = normalizeText(content);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        if (normalized.length() <= SUMMARY_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SUMMARY_MAX_LENGTH);
    }

    /**
     * 当 DeepSeek 调用失败或返回空时，提供最小结构化 JSON，保证 deepseek_analysis 字段可持续写入。
     */
    private String buildFallbackDeepseekJson(String title, String content, String sourceName) {
        try {
            String merged = (safe(title) + "\n" + safe(content)).toLowerCase();
            String method = "未明确";
            if (merged.contains("刷单")) method = "刷单返利";
            else if (merged.contains("公检法")) method = "冒充公检法";
            else if (merged.contains("投资")) method = "虚假投资理财";
            else if (merged.contains("贷款")) method = "网络贷款诈骗";
            else if (merged.contains("客服")) method = "冒充客服退款";
            else if (merged.contains("杀猪盘")) method = "杀猪盘";

            String victim = "未明确";
            if (merged.contains("学生")) victim = "学生";
            else if (merged.contains("老人") || merged.contains("老年")) victim = "老年人";
            else if (merged.contains("财务") || merged.contains("会计") || merged.contains("出纳")) victim = "企业财务人员";
            else if (merged.contains("居民") || merged.contains("市民") || merged.contains("群众")) victim = "普通群众";

            com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode victimGroup = root.putObject("victimGroup");
            victimGroup.put("ageRange", "未明确");
            victimGroup.put("occupation", victim);
            victimGroup.put("gender", "未明确");
            victimGroup.put("regionTag", detectRegionTag(content));

            com.fasterxml.jackson.databind.node.ObjectNode fraudMethod = root.putObject("fraudMethod");
            fraudMethod.put("script", method);
            fraudMethod.put("channel", "未明确");
            fraudMethod.put("paymentChain", "未明确");

            com.fasterxml.jackson.databind.node.ObjectNode fraudTime = root.putObject("fraudTime");
            fraudTime.put("timeRange", "未明确");
            fraudTime.put("durationDays", 0);

            com.fasterxml.jackson.databind.node.ObjectNode fraudLocation = root.putObject("fraudLocation");
            fraudLocation.put("country", "中国");
            fraudLocation.put("province", "未明确");
            fraudLocation.put("city", "未明确");
            fraudLocation.put("district", "未明确");
            fraudLocation.put("scene", merged.contains("网络") || merged.contains("网上") || merged.contains("app") ? "线上" : "未明确");

            root.put("amount", extractAmountSimple(content));
            root.put("caseStatus", "已发生或预警");
            root.put("credibilityScore", 0.35d);
            com.fasterxml.jackson.databind.node.ObjectNode fc = root.putObject("fieldConfidence");
            fc.put("victimGroup", 0.35d);
            fc.put("fraudMethod", 0.35d);
            fc.put("fraudTime", 0.20d);
            fc.put("fraudLocation", 0.30d);
            fc.put("amount", root.path("amount").asDouble() > 0 ? 0.55d : 0.15d);
            fc.put("caseStatus", 0.30d);
            root.put("fallbackBy", "etl-rule");
            root.put("sourceName", safe(sourceName));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"fallbackBy\":\"etl-rule\",\"caseStatus\":\"已发生或预警\"}";
        }
    }

    private static String detectRegionTag(String content) {
        if (!StringUtils.hasText(content)) {
            return "未明确";
        }
        String[] regions = {"北京", "上海", "广东", "深圳", "江苏", "浙江", "山东", "四川", "重庆", "天津", "河南", "湖北", "湖南", "江西", "福建", "河北"};
        for (String r : regions) {
            if (content.contains(r)) {
                return r;
            }
        }
        return "未明确";
    }

    private static double extractAmountSimple(String content) {
        if (!StringUtils.hasText(content)) {
            return 0.0d;
        }
        Pattern p = Pattern.compile("([0-9][0-9,\\.]{0,15})\\s*(亿|万|千)?\\s*元");
        Matcher m = p.matcher(content);
        if (m.find()) {
            try {
                String n = m.group(1).replace(",", "");
                double v = Double.parseDouble(n);
                String unit = m.group(2);
                if ("千".equals(unit)) v *= 1000.0d;
                if ("万".equals(unit)) v *= 10000.0d;
                if ("亿".equals(unit)) v *= 100000000.0d;
                return Math.max(0.0d, v);
            } catch (Exception ignore) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private static final int MAX_DB_URL_LENGTH = 2048;

    private static String truncateUrlForDb(String url) {
        if (!StringUtils.hasText(url)) {
            return url;
        }
        if (url.length() <= MAX_DB_URL_LENGTH) {
            return url;
        }
        return url.substring(0, MAX_DB_URL_LENGTH);
    }

    private static String normalizeTitleForFingerprint(String title) {
        String normalized = normalizeText(title).toLowerCase();
        return normalized.replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    /** 空标题或去符号后无字符时不做标题指纹去重，避免大量不同正文被同一空标题误杀 */
    private static boolean hasDistinctTitleFingerprint(String title) {
        return StringUtils.hasText(normalizeTitleForFingerprint(title));
    }

    private static List<String> resolveListUrls(CrawlerProperties.SourceProperties source) {
        List<String> urls = new ArrayList<>();
        if (source.getListUrls() != null) {
            for (String u : source.getListUrls()) {
                if (StringUtils.hasText(u)) {
                    urls.add(u.trim());
                }
            }
        }
        if (StringUtils.hasText(source.getListUrl())) {
            String one = source.getListUrl().trim();
            if (!urls.contains(one)) {
                urls.add(one);
            }
        }
        if (urls.isEmpty() && StringUtils.hasText(source.getBaseUrl())) {
            urls.add(source.getBaseUrl().trim());
        }
        return urls;
    }

    private static void ensureCheckpointIntegrity(CrawlerCheckpoint checkpoint) {
        if (checkpoint.getSeenUrls() == null) checkpoint.setSeenUrls(new LinkedHashSet<String>());
        if (checkpoint.getSeenContentHashes() == null) checkpoint.setSeenContentHashes(new LinkedHashSet<String>());
        if (checkpoint.getSeenTitleFingerprints() == null) checkpoint.setSeenTitleFingerprints(new LinkedHashSet<String>());
        if (checkpoint.getSourceLastSuccessEpochMs() == null) checkpoint.setSourceLastSuccessEpochMs(new HashMap<String, Long>());
        if (checkpoint.getSourceConsecutiveFailures() == null) checkpoint.setSourceConsecutiveFailures(new HashMap<String, Integer>());
        if (checkpoint.getSourceLastAlertEpochMs() == null) checkpoint.setSourceLastAlertEpochMs(new HashMap<String, Long>());
    }

    private static void purgeInvalidSeenUrls(CrawlerCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getSeenUrls() == null || checkpoint.getSeenUrls().isEmpty()) {
            return;
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String u : checkpoint.getSeenUrls()) {
            if (!StringUtils.hasText(u)) {
                continue;
            }
            if (isAntiSpiderUrl(u)) {
                continue;
            }
            cleaned.add(u);
        }
        checkpoint.setSeenUrls(cleaned);
    }

    private static void markSourceSuccess(CrawlerProperties.SourceProperties source, CrawlerCheckpoint checkpoint) {
        if (source == null || !StringUtils.hasText(source.getName())) {
            return;
        }
        String key = source.getName();
        checkpoint.getSourceLastSuccessEpochMs().put(key, System.currentTimeMillis());
        checkpoint.getSourceConsecutiveFailures().put(key, 0);
    }

    private static void markSourceFailure(CrawlerProperties.SourceProperties source, CrawlerCheckpoint checkpoint) {
        if (source == null || !StringUtils.hasText(source.getName())) {
            return;
        }
        String key = source.getName();
        Integer old = checkpoint.getSourceConsecutiveFailures().get(key);
        checkpoint.getSourceConsecutiveFailures().put(key, old == null ? 1 : old + 1);
    }

    private void checkSourceAlerts(List<CrawlerProperties.SourceProperties> sources, Map<String, Integer> sourceCandidateCount, CrawlerCheckpoint checkpoint) {
        long now = System.currentTimeMillis();
        long thresholdMs = Math.max(1, crawlerProperties.getSourceRepairAlertHours()) * 3600_000L;
        for (CrawlerProperties.SourceProperties source : sources) {
            String sourceName = source.getName();
            if (!StringUtils.hasText(sourceName)) {
                continue;
            }
            int count = sourceCandidateCount.getOrDefault(sourceName, 0);
            if (count > 0) {
                continue;
            }
            long lastSuccess = checkpoint.getSourceLastSuccessEpochMs().getOrDefault(sourceName, 0L);
            int failures = checkpoint.getSourceConsecutiveFailures().getOrDefault(sourceName, 0);
            if (now - lastSuccess < thresholdMs || failures < 3) {
                continue;
            }
            long lastAlert = checkpoint.getSourceLastAlertEpochMs().getOrDefault(sourceName, 0L);
            if (!shouldTriggerSourceAlert(now, lastSuccess, lastAlert, thresholdMs, failures)) {
                continue;
            }
            checkpoint.getSourceLastAlertEpochMs().put(sourceName, now);
            log.error("Crawler source alert triggered: source={}, emptyCandidatesForHours>={}, consecutiveFailures={}, autoRepairFallback=generic-selectors",
                    sourceName, crawlerProperties.getSourceRepairAlertHours(), failures);
        }
    }

    static boolean shouldTriggerSourceAlert(long now, long lastSuccessEpochMs, long lastAlertEpochMs, long thresholdMs, int failures) {
        if (failures < 3) {
            return false;
        }
        if (now - lastSuccessEpochMs < thresholdMs) {
            return false;
        }
        return now - lastAlertEpochMs >= thresholdMs;
    }

    private static class RelevanceScore {
        private final double titleKeywordScore;
        private final double semanticScore;
        private final double freshnessScore;
        private final double sourceScore;
        private final double featureScore;
        private final double finalScore;
        private final List<String> tags;

        private RelevanceScore(double titleKeywordScore, double semanticScore, double freshnessScore, double sourceScore, double featureScore, double finalScore, List<String> tags) {
            this.titleKeywordScore = titleKeywordScore;
            this.semanticScore = semanticScore;
            this.freshnessScore = freshnessScore;
            this.sourceScore = sourceScore;
            this.featureScore = featureScore;
            this.finalScore = finalScore;
            this.tags = tags;
        }

        private double getFinalScore() {
            return finalScore;
        }

        private List<String> getTags() {
            return tags;
        }
    }

    private static class ListFetchOutcome {
        private final List<String> urls;
        private final boolean fetchSucceeded;

        private ListFetchOutcome(List<String> urls, boolean fetchSucceeded) {
            this.urls = urls;
            this.fetchSucceeded = fetchSucceeded;
        }

        private static ListFetchOutcome success(List<String> urls) {
            return new ListFetchOutcome(urls == null ? Collections.<String>emptyList() : urls, true);
        }

        private static ListFetchOutcome failed() {
            return new ListFetchOutcome(Collections.<String>emptyList(), false);
        }

        private List<String> getUrls() {
            return urls;
        }

        private boolean isFetchSucceeded() {
            return fetchSucceeded;
        }
    }

    static boolean isWithinRecentRange(LocalDateTime publishTime, int maxArticleAgeDays, boolean allowUnknownPublishTime) {
        if (publishTime == null) {
            return allowUnknownPublishTime;
        }
        int days = maxArticleAgeDays > 0 ? maxArticleAgeDays : 90;
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        return !publishTime.isBefore(threshold);
    }

    private static String extractTitle(Document doc, String selector) {
        String selected = textBySelector(doc, selector, "h1, .article-title, .title, title");
        if (StringUtils.hasText(selected)) {
            return selected;
        }
        Element ogTitle = doc.selectFirst("meta[property=og:title], meta[name=og:title]");
        if (ogTitle != null && StringUtils.hasText(ogTitle.attr("content"))) {
            return normalizeText(ogTitle.attr("content"));
        }
        Element twTitle = doc.selectFirst("meta[name=twitter:title]");
        if (twTitle != null && StringUtils.hasText(twTitle.attr("content"))) {
            return normalizeText(twTitle.attr("content"));
        }
        return normalizeText(doc.title());
    }

    private static String extractPublishTimeText(Document doc, String selector) {
        String selected = textBySelector(doc, selector, ".date, .time, [class*=time], [class*=date], time");
        if (StringUtils.hasText(selected)) {
            return selected;
        }
        Elements metas = doc.select("meta[property=article:published_time], meta[name=publishdate], meta[name=pubdate], meta[name=date], meta[name=article:published_time]");
        for (Element meta : metas) {
            String content = normalizeText(meta.attr("content"));
            if (StringUtils.hasText(content)) {
                return content;
            }
        }
        Element timeEl = doc.selectFirst("time[datetime]");
        if (timeEl != null && StringUtils.hasText(timeEl.attr("datetime"))) {
            return normalizeText(timeEl.attr("datetime"));
        }
        String bodyText = normalizeText(doc.body() == null ? "" : doc.body().text());
        Matcher matcher = DATE_TIME_PATTERN.matcher(bodyText);
        if (matcher.find()) {
            return normalizeText(matcher.group(1));
        }
        return "";
    }

    private static String extractArticleContent(Document doc, String selector) {
        Document cleaned = doc.clone();
        cleaned.select("script,style,noscript,iframe,svg,header,footer,nav,aside,form,button,input,textarea").remove();
        String selected = longestTextBySelector(cleaned, selector);
        if (StringUtils.hasText(selected) && selected.length() >= MIN_ARTICLE_CONTENT_LENGTH) {
            return selected;
        }

        Elements candidates = cleaned.select("article, main, [id*=content], [class*=content], [id*=article], [class*=article], .post, .detail, section, div");
        String bestText = "";
        double bestScore = -1d;
        for (Element candidate : candidates) {
            String text = normalizeText(candidate.text());
            if (text.length() < MIN_ARTICLE_CONTENT_LENGTH) {
                continue;
            }
            double score = scoreContentBlock(candidate, text);
            if (score > bestScore) {
                bestScore = score;
                bestText = text;
            }
        }
        if (StringUtils.hasText(bestText)) {
            return bestText;
        }
        return normalizeText(cleaned.body() == null ? "" : cleaned.body().text());
    }

    private static double scoreContentBlock(Element candidate, String text) {
        int textLength = text.length();
        int punctuationCount = countMatches(text, "。") + countMatches(text, "，") + countMatches(text, "；") + countMatches(text, "：");
        int paragraphCount = candidate.select("p").size();
        int fraudSignalCount = keywordHitCount(text.toLowerCase(), DEFAULT_CORE_KEYWORDS)
                + keywordHitCount(text.toLowerCase(), DEFAULT_TYPE_KEYWORDS)
                + keywordHitCount(text.toLowerCase(), DEFAULT_NEWS_CONTEXT_KEYWORDS);
        int noiseCount = keywordHitCount(text, NOISE_BLOCK_HINTS);
        return textLength + punctuationCount * 20.0d + paragraphCount * 60.0d + fraudSignalCount * 120.0d - noiseCount * 200.0d;
    }

    private static int countMatches(String text, String token) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(token)) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(token, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + token.length();
        }
        return count;
    }

    private static boolean hitsAny(String source, List<String> keywords) {
        if (!StringUtils.hasText(source) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            if (source.contains(keyword.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static int keywordHitCount(String source, List<String> keywords) {
        if (!StringUtils.hasText(source) || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            if (source.contains(keyword.trim().toLowerCase())) {
                total++;
            }
        }
        return total;
    }

    private static String longestTextBySelector(Document doc, String selector) {
        String sel = StringUtils.hasText(selector) ? normalizeCssSelector(selector) : "article, .content, #content, [class*=content], body";
        Elements elements = safeSelect(doc, sel);
        String longest = "";
        for (Element element : elements) {
            String text = normalizeText(element.text());
            if (text.length() > longest.length()) {
                longest = text;
            }
        }
        return longest;
    }

    private static String textBySelector(Document doc, String selector, String fallbackSelector) {
        String primary = StringUtils.hasText(selector) ? normalizeCssSelector(selector) : null;
        String fallback = StringUtils.hasText(fallbackSelector) ? normalizeCssSelector(fallbackSelector) : null;
        Element el = safeSelectFirst(doc, primary);
        if (el == null && StringUtils.hasText(fallback)) {
            el = safeSelectFirst(doc, fallback);
        }
        if (el == null) {
            return "";
        }
        return normalizeText(el.text());
    }

    /**
     * Trims and removes a trailing comma that would leave Jsoup with an empty sub-selector (e.g. mis-parsed YAML "h1, ").
     */
    private static String normalizeCssSelector(String selector) {
        if (!StringUtils.hasText(selector)) {
            return "";
        }
        String s = selector.trim();
        while (s.endsWith(",")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static Element safeSelectFirst(Document doc, String selector) {
        if (doc == null || !StringUtils.hasText(selector)) {
            return null;
        }
        try {
            return doc.selectFirst(selector);
        } catch (Selector.SelectorParseException e) {
            return null;
        }
    }

    private static Elements safeSelect(Document doc, String selector) {
        if (doc == null || !StringUtils.hasText(selector)) {
            return new Elements();
        }
        try {
            return doc.select(selector);
        } catch (Selector.SelectorParseException e) {
            return new Elements();
        }
    }

    private static boolean isLikelyArticleUrl(String href) {
        if (!StringUtils.hasText(href)) {
            return false;
        }
        String lower = href.trim().toLowerCase();
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:")) {
            return false;
        }
        if (lower.matches(".*\\.(css|js|json|xml|jpg|jpeg|png|gif|webp|svg|pdf|doc|docx|xls|xlsx|zip|rar)(\\?.*)?$")) {
            return false;
        }
        return !lower.contains("/login")
                && !lower.contains("/register")
                && !lower.contains("/search")
                && !lower.contains("/tag/")
                && !lower.contains("/video/")
                && !lower.contains("/live/")
                && !lower.contains("/comment")
                && !lower.contains("javascript:void");
    }

    private static String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static CrawlerProperties.SourceProperties matchSource(String url, List<CrawlerProperties.SourceProperties> sources) {
        CrawlerProperties.SourceProperties exactSogou = matchSogouSourceByQuery(url, sources);
        if (exactSogou != null) {
            return exactSogou;
        }
        CrawlerProperties.SourceProperties bestPrefix = null;
        int bestPrefixLen = -1;
        for (CrawlerProperties.SourceProperties s : sources) {
            if (StringUtils.hasText(s.getBaseUrl()) && url.startsWith(s.getBaseUrl())) {
                int len = s.getBaseUrl().length();
                if (len > bestPrefixLen) {
                    bestPrefixLen = len;
                    bestPrefix = s;
                }
            }
        }
        if (bestPrefix != null) {
            return bestPrefix;
        }
        for (CrawlerProperties.SourceProperties s : sources) {
            if (matchesArticleHost(url, s)) {
                return s;
            }
        }
        return null;
    }

    private static CrawlerProperties.SourceProperties matchSourceForLocalSogouUrl(String url, List<CrawlerProperties.SourceProperties> sources) {
        if (!StringUtils.hasText(url) || sources == null || sources.isEmpty()) {
            return null;
        }
        if (!url.startsWith(LOCAL_SOGOU_URL_PREFIX)) {
            return null;
        }
        String tail = url.substring(LOCAL_SOGOU_URL_PREFIX.length());
        int slash = tail.indexOf('/');
        String encodedSource = slash > 0 ? tail.substring(0, slash) : tail;
        if (!StringUtils.hasText(encodedSource)) {
            return null;
        }
        for (CrawlerProperties.SourceProperties source : sources) {
            if (source == null || !StringUtils.hasText(source.getName())) {
                continue;
            }
            String normalized = normalizeText(source.getName()).replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (encodedSource.equals(normalized)) {
                return source;
            }
        }
        for (CrawlerProperties.SourceProperties source : sources) {
            if (isSogouSource(source)) {
                return source;
            }
        }
        return null;
    }

    private static CrawlerProperties.SourceProperties matchSogouSourceByQuery(String url, List<CrawlerProperties.SourceProperties> sources) {
        if (!StringUtils.hasText(url) || sources == null || sources.isEmpty()) {
            return null;
        }
        String lower = url.toLowerCase();
        if (!lower.contains("weixin.sogou.com/link")) {
            return null;
        }
        String queryInArticle = decodeQueryForDisplay(queryParam(url, "query"));
        if (!StringUtils.hasText(queryInArticle)) {
            return null;
        }
        String q = queryInArticle.replaceAll("\\s+", "").toLowerCase();
        for (CrawlerProperties.SourceProperties s : sources) {
            if (s == null || !StringUtils.hasText(s.getStrategy())) {
                continue;
            }
            String strategy = s.getStrategy().trim().toLowerCase();
            if (!strategy.contains("sogou")) {
                continue;
            }
            List<String> listUrls = resolveListUrls(s);
            for (String lu : listUrls) {
                String srcQuery = decodeQueryForDisplay(queryParam(lu, "query"));
                if (!StringUtils.hasText(srcQuery)) {
                    continue;
                }
                String sq = srcQuery.replaceAll("\\s+", "").toLowerCase();
                if (q.equals(sq)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static boolean matchesArticleHost(String url, CrawlerProperties.SourceProperties source) {
        if (source == null || source.getArticleHosts() == null || source.getArticleHosts().isEmpty()) {
            return false;
        }
        String host = hostOf(url);
        if (!StringUtils.hasText(host)) {
            return false;
        }
        String h = host.toLowerCase();
        for (String pattern : source.getArticleHosts()) {
            if (!StringUtils.hasText(pattern)) continue;
            String p = pattern.trim().toLowerCase();
            if (h.equals(p) || h.endsWith("." + p)) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            URI u = new URI(url.trim());
            String host = u.getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    private void randomDelay() {
        long min = Math.max(0L, crawlerProperties.getMinDelayMs());
        long max = Math.max(min, crawlerProperties.getMaxDelayMs());
        long delay = ThreadLocalRandom.current().nextLong(min, max + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void trimSeen(CrawlerCheckpoint checkpoint) {
        int max = 5000;
        if (checkpoint.getSeenUrls().size() > max) {
            int removeCount = checkpoint.getSeenUrls().size() - max;
            Iterator<String> it = checkpoint.getSeenUrls().iterator();
            while (removeCount > 0 && it.hasNext()) {
                it.next();
                it.remove();
                removeCount--;
            }
        }
        if (checkpoint.getSeenContentHashes().size() > max) {
            int removeCount = checkpoint.getSeenContentHashes().size() - max;
            Iterator<String> it = checkpoint.getSeenContentHashes().iterator();
            while (removeCount > 0 && it.hasNext()) {
                it.next();
                it.remove();
                removeCount--;
            }
        }
        if (checkpoint.getSeenTitleFingerprints() != null && checkpoint.getSeenTitleFingerprints().size() > max) {
            int removeCount = checkpoint.getSeenTitleFingerprints().size() - max;
            Iterator<String> it = checkpoint.getSeenTitleFingerprints().iterator();
            while (removeCount > 0 && it.hasNext()) {
                it.next();
                it.remove();
                removeCount--;
            }
        }
    }

    private static String normalizeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith(",") || trimmed.endsWith(")") || trimmed.endsWith("]") || trimmed.endsWith("}") || trimmed.endsWith("，") || trimmed.endsWith("。")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        int hash = trimmed.indexOf('#');
        if (hash >= 0) {
            trimmed = trimmed.substring(0, hash);
        }

        int q = trimmed.indexOf('?');
        if (q < 0) {
            return trimmed;
        }
        String base = trimmed.substring(0, q);
        String query = trimmed.substring(q + 1);
        if (!StringUtils.hasText(query)) {
            return base;
        }
        StringBuilder kept = new StringBuilder();
        String[] parts = query.split("&");
        for (String p : parts) {
            if (!StringUtils.hasText(p)) continue;
            String key = p;
            int eq = p.indexOf('=');
            if (eq >= 0) {
                key = p.substring(0, eq);
            }
            String k = key.toLowerCase();
            if (k.startsWith("utm_") || k.equals("from") || k.equals("spm") || k.equals("source") || k.equals("ref")) {
                continue;
            }
            if (kept.length() > 0) kept.append('&');
            kept.append(p);
        }
        if (kept.length() == 0) {
            return base;
        }
        return base + "?" + kept;
    }
}
