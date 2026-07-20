package com.magicvvu.fanzha.backend.service.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class CrawlerService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final String[] KEYWORDS = {"诈骗", "电信诈骗", "网络诈骗", "最新骗局"};

    public List<NewsItem> crawlBaiduNews() {
        List<NewsItem> items = new ArrayList<>();
        String baseUrl = "https://www.baidu.com/s?tn=news&rtt=1&bsst=1&cl=2&wd=";

        for (String keyword : KEYWORDS) {
            try {
                // Sleep randomly 1-3s
                Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 3000));

                String url = baseUrl + java.net.URLEncoder.encode(keyword, "UTF-8");
                log.info("Crawling Baidu News: {}", url);

                Document doc = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .referrer("https://www.baidu.com/")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("Connection", "keep-alive")
                        .timeout(10000)
                        .get();

                String titleText = doc.title() != null ? doc.title() : "";
                String bodyText = doc.body() != null ? doc.body().text() : "";
                if (titleText.contains("百度安全验证") || bodyText.contains("百度安全验证") || bodyText.contains("请完成下方验证")) {
                    log.warn("Baidu returned verification page for keyword: {}", keyword);
                    continue;
                }

                Elements newsLinks = doc.select("div.result-op.c-container, div.result.c-container, div.result");
                if (newsLinks.isEmpty()) {
                    log.warn("No search results parsed for keyword: {}", keyword);
                }

                for (Element el : newsLinks) {
                     Element a = el.selectFirst("a[href]");
                     if (a == null) continue;

                     String title = a.text();
                     String link = a.absUrl("href");
                     if (link == null || link.trim().isEmpty()) {
                         link = a.attr("href");
                     }

                     String source = el.select(".c-color-gray.c-font-normal.c-gap-right").text();
                     String summary = el.text();

                     if (title.isEmpty() || link.isEmpty()) continue;

                     NewsItem item = new NewsItem();
                     item.setTitle(title);
                     item.setUrl(link);
                     item.setSource(source);
                     item.setPublishTime(LocalDateTime.now()); // Simplify for now

                     // Fetch content (Simplified)
                     try {
                         Document articleDoc = Jsoup.connect(link)
                                 .userAgent(USER_AGENT)
                                 .referrer(url)
                                 .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                                 .timeout(12000)
                                 .get();
                         item.setContent(articleDoc.body().text());
                     } catch (Exception e) {
                         log.warn("Failed to fetch article content: {}", link);
                         String fallback = (title + " " + summary).replaceAll("\\s+", " ").trim();
                         item.setContent(fallback);
                     }

                     items.add(item);
                }
            } catch (Exception e) {
                log.error("Error crawling keyword: " + keyword, e);
            }
        }
        return items;
    }
}
