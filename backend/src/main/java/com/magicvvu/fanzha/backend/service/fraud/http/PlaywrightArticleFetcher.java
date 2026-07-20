package com.magicvvu.fanzha.backend.service.fraud.http;

import com.magicvvu.fanzha.backend.config.CrawlerProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class PlaywrightArticleFetcher implements DisposableBean {
    private final CrawlerProperties properties;
    private final Object lock = new Object();

    private Playwright playwright;
    private Browser browser;
    private BrowserContext sharedContext;
    private volatile boolean initFailed = false;

    public PlaywrightArticleFetcher(CrawlerProperties properties) {
        this.properties = properties;
    }

    public FetchResult fetch(String url, String referer) {
        CrawlerProperties.PlaywrightProperties pw = properties.getPlaywright();
        if (pw == null || !pw.isEnabled() || !StringUtils.hasText(url) || initFailed) {
            return null;
        }
        try {
            ensureBrowser(pw);
        } catch (Exception e) {
            initFailed = true;
            log.error("Playwright init failed; fallback to OkHttp. Install browser by running: "
                    + "mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\". err={}", e.getMessage());
            return null;
        }

        if (sharedContext == null) {
            return null;
        }
        maybeWarmSogouSession(url, referer, pw);
        Page page = sharedContext.newPage();
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(Math.max(10000, pw.getNavigationTimeoutMs()))
                    .setReferer(StringUtils.hasText(referer) ? referer : null)
                    .setWaitUntil(parseWaitUntil(pw.getWaitUntil())));
            try {
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(Math.min(12000, pw.getNavigationTimeoutMs())));
            } catch (Exception ignore) {
            }
            if (StringUtils.hasText(page.url()) && page.url().contains("mp.weixin.qq.com")) {
                try {
                    page.waitForSelector("#js_content, .rich_media_content, #img-content",
                            new Page.WaitForSelectorOptions().setTimeout(Math.min(25000, pw.getNavigationTimeoutMs())));
                } catch (Exception ignore) {
                }
            }
            if (pw.getExtraSettleMs() > 0) {
                page.waitForTimeout(pw.getExtraSettleMs());
            }
            String html = extractContentWithRetry(page, Math.min(8000L, Math.max(1500L, pw.getExtraSettleMs())));
            if (!StringUtils.hasText(html)) {
                return null;
            }
            return new FetchResult(url, 200, page.url(), "text/html", html);
        } catch (Exception e) {
            log.warn("Playwright fetch failed url={} err={}", abbreviate(url), e.getMessage());
            return null;
        } finally {
            try {
                page.close();
            } catch (Exception ignore) {
            }
        }
    }

    private void ensureBrowser(CrawlerProperties.PlaywrightProperties pw) {
        synchronized (lock) {
            if (sharedContext != null) {
                try {
                    Page probe = sharedContext.newPage();
                    probe.close();
                    return;
                } catch (Exception e) {
                    try {
                        sharedContext.close();
                    } catch (Exception ignored) {
                    }
                    sharedContext = null;
                }
            }
            if (browser != null && browser.isConnected()) {
                return;
            }
            if (sharedContext != null) {
                try {
                    sharedContext.close();
                } catch (Exception ignored) {
                }
                sharedContext = null;
            }
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception ignored) {
                }
                browser = null;
            }
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (Exception ignored) {
                }
                playwright = null;
            }
            String browsersPath = resolveBrowsersPath(pw);
            Playwright.CreateOptions createOptions = new Playwright.CreateOptions();
            Map<String, String> env = new HashMap<>();
            env.putAll(System.getenv());
            if (StringUtils.hasText(browsersPath)) {
                File pathDir = new File(browsersPath);
                if (!pathDir.exists() && !pathDir.mkdirs()) {
                    log.warn("Failed to create Playwright browsers path: {}", browsersPath);
                }
                env.put("PLAYWRIGHT_BROWSERS_PATH", browsersPath);
            }
            createOptions.setEnv(env);
            playwright = Playwright.create(createOptions);
            boolean headless = pw == null || pw.isHeadless();
            String userDataDir = pw == null ? "" : safe(pw.getUserDataDir());
            if (StringUtils.hasText(userDataDir)) {
                File dir = new File(userDataDir);
                if (!dir.exists() && !dir.mkdirs()) {
                    log.warn("Failed to create Playwright user-data-dir: {}", userDataDir);
                }
                BrowserType.LaunchPersistentContextOptions persistentOpts = new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(headless)
                        .setUserAgent(pickUserAgent())
                        .setLocale("zh-CN")
                        .setExtraHTTPHeaders(buildHeaders(null))
                        .setIgnoreHTTPSErrors(true)
                        .setArgs(java.util.Arrays.asList("--disable-blink-features=AutomationControlled", "--no-sandbox"));
                applyProxyIfNeeded(persistentOpts);
                sharedContext = playwright.chromium().launchPersistentContext(Paths.get(userDataDir), persistentOpts);
                browser = sharedContext.browser();
            } else {
                BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                        .setHeadless(headless)
                        .setArgs(java.util.Arrays.asList("--disable-blink-features=AutomationControlled", "--no-sandbox"));
                browser = playwright.chromium().launch(launchOptions);

                Browser.NewContextOptions ctxOptions = new Browser.NewContextOptions()
                        .setUserAgent(pickUserAgent())
                        .setLocale("zh-CN")
                        .setExtraHTTPHeaders(buildHeaders(null))
                        .setIgnoreHTTPSErrors(true);
                applyProxyIfNeeded(ctxOptions);
                sharedContext = browser.newContext(ctxOptions);
            }
            log.info("Playwright browser launched with browsers path={}",
                    StringUtils.hasText(browsersPath) ? browsersPath : "(default)");
        }
    }

    /**
     * For sogou link pages, warm the corresponding list page in same context
     * so anti-bot cookies become available before opening /link target.
     */
    private void maybeWarmSogouSession(String url, String referer, CrawlerProperties.PlaywrightProperties pw) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(referer) || sharedContext == null) {
            return;
        }
        String lowerUrl = url.toLowerCase();
        String lowerRef = referer.toLowerCase();
        if (!(lowerUrl.contains("weixin.sogou.com/link") && lowerRef.contains("weixin.sogou.com/weixin"))) {
            return;
        }
        Page warmup = null;
        try {
            warmup = sharedContext.newPage();
            warmup.navigate(referer, new Page.NavigateOptions()
                    .setTimeout(Math.min(15000, Math.max(6000, pw.getNavigationTimeoutMs())))
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            warmup.waitForTimeout(Math.max(300, Math.min(1200, pw.getExtraSettleMs())));
        } catch (Exception ignore) {
        } finally {
            if (warmup != null) {
                try {
                    warmup.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private String extractContentWithRetry(Page page, long settleMs) {
        for (int i = 0; i < 3; i++) {
            try {
                return page.content();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (msg.contains("page is navigating") || msg.contains("changing the content")) {
                    try {
                        page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                                new Page.WaitForLoadStateOptions().setTimeout(Math.max(800, settleMs)));
                    } catch (Exception ignore) {
                    }
                    try {
                        page.waitForTimeout(Math.max(500, settleMs));
                    } catch (Exception ignore) {
                    }
                    continue;
                }
                return "";
            }
        }
        return "";
    }

    private void applyProxyIfNeeded(Browser.NewContextOptions ctxOptions) {
        if (properties.getProxy() != null && properties.getProxy().isEnabled()
                && StringUtils.hasText(properties.getProxy().getHost())
                && properties.getProxy().getPort() > 0) {
            String scheme = "http";
            if (StringUtils.hasText(properties.getProxy().getType())
                    && properties.getProxy().getType().toLowerCase().startsWith("socks")) {
                scheme = "socks5";
            }
            Proxy px = new Proxy(scheme + "://" + properties.getProxy().getHost().trim() + ":" + properties.getProxy().getPort());
            if (StringUtils.hasText(properties.getProxy().getUsername())) {
                px.setUsername(properties.getProxy().getUsername().trim());
                px.setPassword(properties.getProxy().getPassword() == null ? "" : properties.getProxy().getPassword());
            }
            ctxOptions.setProxy(px);
        }
    }

    private void applyProxyIfNeeded(BrowserType.LaunchPersistentContextOptions ctxOptions) {
        if (properties.getProxy() != null && properties.getProxy().isEnabled()
                && StringUtils.hasText(properties.getProxy().getHost())
                && properties.getProxy().getPort() > 0) {
            String scheme = "http";
            if (StringUtils.hasText(properties.getProxy().getType())
                    && properties.getProxy().getType().toLowerCase().startsWith("socks")) {
                scheme = "socks5";
            }
            Proxy px = new Proxy(scheme + "://" + properties.getProxy().getHost().trim() + ":" + properties.getProxy().getPort());
            if (StringUtils.hasText(properties.getProxy().getUsername())) {
                px.setUsername(properties.getProxy().getUsername().trim());
                px.setPassword(properties.getProxy().getPassword() == null ? "" : properties.getProxy().getPassword());
            }
            ctxOptions.setProxy(px);
        }
    }

    private String resolveBrowsersPath(CrawlerProperties.PlaywrightProperties pw) {
        if (pw != null && StringUtils.hasText(pw.getBrowsersPath())) {
            return pw.getBrowsersPath().trim();
        }
        String envPath = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (StringUtils.hasText(envPath)) {
            return envPath.trim();
        }
        return "";
    }

    private Map<String, String> buildHeaders(String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        if (StringUtils.hasText(referer)) {
            headers.put("Referer", referer);
        }
        if (properties.getExtraHeaders() != null) {
            for (Map.Entry<String, String> e : properties.getExtraHeaders().entrySet()) {
                if (StringUtils.hasText(e.getKey()) && StringUtils.hasText(e.getValue())) {
                    headers.put(e.getKey().trim(), e.getValue().trim());
                }
            }
        }
        return headers;
    }

    private String pickUserAgent() {
        if (properties.getUserAgents() == null || properties.getUserAgents().isEmpty()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
        }
        int idx = ThreadLocalRandom.current().nextInt(properties.getUserAgents().size());
        return properties.getUserAgents().get(idx);
    }

    private static WaitUntilState parseWaitUntil(String waitUntil) {
        if (!StringUtils.hasText(waitUntil)) {
            return WaitUntilState.DOMCONTENTLOADED;
        }
        switch (waitUntil.trim().toLowerCase()) {
            case "load":
                return WaitUntilState.LOAD;
            case "networkidle":
                return WaitUntilState.NETWORKIDLE;
            case "commit":
                return WaitUntilState.COMMIT;
            default:
                return WaitUntilState.DOMCONTENTLOADED;
        }
    }

    private static String abbreviate(String url) {
        if (url == null) return "";
        return url.length() <= 120 ? url : url.substring(0, 120) + "...";
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            if (sharedContext != null) {
                try {
                    sharedContext.close();
                } catch (Exception ignored) {
                }
                sharedContext = null;
            }
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception ignored) {
                }
                browser = null;
            }
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (Exception ignored) {
                }
                playwright = null;
            }
        }
    }
}
