package com.magicvvu.fanzha.backend.service.fraud;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

public class FraudNewsEtlServiceFilterTest {

    @Test
    public void containsFraudKeywordMatchesTitleOrContent() {
        boolean matchedByTitle = FraudNewsEtlService.containsFraudKeyword(
                "警方通报电信诈骗案件",
                "正文",
                Arrays.asList("诈骗", "电信诈骗")
        );
        Assertions.assertTrue(matchedByTitle);

        boolean matchedByContent = FraudNewsEtlService.containsFraudKeyword(
                "普通社会新闻",
                "该案涉及网络诈骗和资金转移",
                Arrays.asList("网络诈骗")
        );
        Assertions.assertTrue(matchedByContent);

        boolean notMatched = FraudNewsEtlService.containsFraudKeyword(
                "体育新闻",
                "球队取得胜利",
                Arrays.asList("电信诈骗", "金融诈骗")
        );
        Assertions.assertFalse(notMatched);
    }

    @Test
    public void containsFraudKeywordUsesDefaultKeywordsWhenConfigEmpty() {
        boolean matched = FraudNewsEtlService.containsFraudKeyword(
                "警方破获刷单诈骗链条",
                "正文",
                Collections.emptyList()
        );
        Assertions.assertTrue(matched);
    }

    @Test
    public void containsFraudKeywordRelaxedCoreOrTypeWithNews() {
        Assertions.assertTrue(FraudNewsEtlService.containsFraudKeyword(
                "简讯",
                "本案认定为诈骗罪相关讨论。",
                Collections.emptyList()));

        Assertions.assertFalse(FraudNewsEtlService.containsFraudKeyword(
                "股市分析",
                "今日大盘投资理财策略综述",
                Collections.emptyList()));

        Assertions.assertTrue(FraudNewsEtlService.containsFraudKeyword(
                "警方发布防骗预警",
                "近期骗子伪装客服实施诈骗，警方提醒勿点击陌生链接",
                Collections.emptyList()));

        Assertions.assertTrue(FraudNewsEtlService.containsFraudKeyword(
                "提醒",
                "市民需提高警惕，谨防刷单返利与冒充客服类网络诈骗。",
                Collections.emptyList()));
    }

    @Test
    public void isWithinRecentRangeHonorsDaysAndUnknownPolicy() {
        LocalDateTime now = LocalDateTime.now();
        Assertions.assertTrue(FraudNewsEtlService.isWithinRecentRange(now.minusDays(3), 7, true));
        Assertions.assertFalse(FraudNewsEtlService.isWithinRecentRange(now.minusDays(30), 7, true));
        Assertions.assertTrue(FraudNewsEtlService.isWithinRecentRange(null, 7, true));
        Assertions.assertFalse(FraudNewsEtlService.isWithinRecentRange(null, 7, false));
    }

    @Test
    public void semanticAndFeatureScoreAreHigherForFraudLikeContent() {
        String fraudContent = "警方通报一起电信诈骗案件，嫌疑人冒充客服诱导受害人转账并索取验证码，涉案金额数万元。";
        String normalContent = "学校举办春季运动会，师生积极参与，现场气氛热烈。";

        double fraudSemantic = FraudNewsEtlService.semanticSimilarity(fraudContent);
        double normalSemantic = FraudNewsEtlService.semanticSimilarity(normalContent);
        Assertions.assertTrue(fraudSemantic > normalSemantic);

        double fraudFeature = FraudNewsEtlService.fraudFeatureScore("反诈提醒", fraudContent);
        double normalFeature = FraudNewsEtlService.fraudFeatureScore("校园新闻", normalContent);
        Assertions.assertTrue(fraudFeature > normalFeature);
    }

    @Test
    public void extractFraudTagsShouldProduceStructuredTags() {
        String text = "广东警方破获冒充公检法电信诈骗案，涉案金额10万元，提醒市民谨防验证码诈骗。";
        java.util.List<String> tags = FraudNewsEtlService.extractFraudTags("反诈通报", text);
        Assertions.assertTrue(tags.contains("电信诈骗"));
        Assertions.assertTrue(tags.contains("广东"));
        Assertions.assertTrue(tags.contains("金额相关"));
    }

    @Test
    public void shouldTriggerSourceAlertHonorsThresholdAndCooldown() {
        long now = System.currentTimeMillis();
        long threshold = 24L * 3600_000L;
        Assertions.assertTrue(FraudNewsEtlService.shouldTriggerSourceAlert(now, now - threshold - 1000, 0L, threshold, 3));
        Assertions.assertFalse(FraudNewsEtlService.shouldTriggerSourceAlert(now, now - threshold - 1000, now - 1000, threshold, 3));
        Assertions.assertFalse(FraudNewsEtlService.shouldTriggerSourceAlert(now, now - threshold + 1000, 0L, threshold, 3));
        Assertions.assertFalse(FraudNewsEtlService.shouldTriggerSourceAlert(now, now - threshold - 1000, 0L, threshold, 2));
    }
}
