package com.magicvvu.fanzha.backend.service.fraud.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magicvvu.fanzha.backend.config.FraudPipelineProperties;
import com.magicvvu.fanzha.backend.util.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class DeepSeekFraudCaseExtractor {
    private final DeepSeekClient deepSeekClient;
    private final FraudPipelineProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern AGE_PATTERN = Pattern.compile("(\\d{1,2})\\s*岁");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("([0-9][0-9,\\.]{0,15})\\s*(亿|万|千)?\\s*元");
    private static final Pattern CITY_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,12}市)");
    private static final List<String> PROVINCES = Arrays.asList(
            "北京市", "天津市", "上海市", "重庆市",
            "河北省", "山西省", "辽宁省", "吉林省", "黑龙江省",
            "江苏省", "浙江省", "安徽省", "福建省", "江西省",
            "山东省", "河南省", "湖北省", "湖南省", "广东省",
            "海南省", "四川省", "贵州省", "云南省", "陕西省",
            "甘肃省", "青海省", "台湾省", "内蒙古自治区", "广西壮族自治区",
            "西藏自治区", "宁夏回族自治区", "新疆维吾尔自治区", "香港特别行政区", "澳门特别行政区"
    );

    public FraudCaseExtraction extract(String title, String content) throws Exception {
        if (!properties.isEnabled()) {
            return null;
        }
        String safeTitle = safe(title);
        String safeContent = safe(content);

        String system = buildSystemPrompt();
        String prompt = buildUserPrompt(safeTitle, safeContent);
        try {
            String resp = callDeepSeekWithRetry(system, prompt);
            FraudCaseExtraction extraction = parseExtraction(resp);
            return normalizeAndBackfill(extraction, safeTitle, safeContent);
        } catch (Exception ignore) {
            // 模型调用失败时也返回结构化结果，避免 deepseek_analysis 为空导致入库信息缺失
            return normalizeAndBackfill(null, safeTitle, safeContent);
        }
    }

    private String callDeepSeekWithRetry(String system, String prompt) throws Exception {
        int maxRetries = Math.max(1, properties.getMaxRetries());
        long backoffMs = 500L;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return deepSeekClient.chat(system, prompt, StringUtils.hasText(properties.getModel()) ? properties.getModel() : null);
            } catch (Exception e) {
                lastError = e;
                if (!shouldRetry(e) || attempt == maxRetries) {
                    throw e;
                }
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2L, 30_000L);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private boolean shouldRetry(Exception e) {
        String message = e.getMessage();
        if (!StringUtils.hasText(message)) {
            return true;
        }
        String normalized = message.toLowerCase();
        return !(normalized.contains("deepseek 请求失败: 400")
                || normalized.contains("deepseek 请求失败: 401")
                || normalized.contains("deepseek 请求失败: 402")
                || normalized.contains("deepseek 请求失败: 403")
                || normalized.contains("deepseek 请求失败: 404"));
    }

    private String buildSystemPrompt() {
        return "你是一个反诈案件信息抽取系统。请从新闻正文中抽取诈骗案件结构化信息，严格只输出 JSON（不要输出 Markdown）。"
                + "必须覆盖并输出以下核心信息：诈骗手段（fraudMethod.script）、诈骗金额（amount）、诈骗对象（victimGroup）、诈骗地区（fraudLocation/victimGroup.regionTag）。"
                + "如果原文未明确，使用“未明确”或 0，不要编造具体数字、姓名或机构。"
                + "返回 JSON schema："
                + "{"
                + "\"victimGroup\":{\"ageRange\":\"\",\"occupation\":\"\",\"gender\":\"\",\"regionTag\":\"\"},"
                + "\"fraudMethod\":{\"script\":\"\",\"channel\":\"\",\"paymentChain\":\"\"},"
                + "\"fraudTime\":{\"timeRange\":\"\",\"durationDays\":0},"
                + "\"fraudLocation\":{\"country\":\"\",\"province\":\"\",\"city\":\"\",\"district\":\"\",\"scene\":\"线上/线下\"},"
                + "\"amount\":0,"
                + "\"caseStatus\":\"\","
                + "\"credibilityScore\":0.0,"
                + "\"fieldConfidence\":{"
                + "\"victimGroup\":0.0,\"fraudMethod\":0.0,\"fraudTime\":0.0,\"fraudLocation\":0.0,\"amount\":0.0,\"caseStatus\":0.0"
                + "}"
                + "}";
    }

    private String buildUserPrompt(String title, String content) {
        String safeTitle = title == null ? "" : title;
        String safeContent = content == null ? "" : content;
        if (safeContent.length() > 12000) {
            safeContent = safeContent.substring(0, 12000);
        }
        return "标题：" + safeTitle + "\n正文：" + safeContent;
    }

    private FraudCaseExtraction parseExtraction(String responseText) throws Exception {
        if (!StringUtils.hasText(responseText)) {
            return null;
        }
        String json = extractJsonObject(responseText);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        return objectMapper.readValue(json, FraudCaseExtraction.class);
    }

    private FraudCaseExtraction normalizeAndBackfill(FraudCaseExtraction extraction, String title, String content) {
        FraudCaseExtraction out = extraction == null ? new FraudCaseExtraction() : extraction;
        if (out.getVictimGroup() == null) out.setVictimGroup(new FraudCaseExtraction.VictimGroup());
        if (out.getFraudMethod() == null) out.setFraudMethod(new FraudCaseExtraction.FraudMethod());
        if (out.getFraudTime() == null) out.setFraudTime(new FraudCaseExtraction.FraudTime());
        if (out.getFraudLocation() == null) out.setFraudLocation(new FraudCaseExtraction.FraudLocation());
        if (out.getFieldConfidence() == null) out.setFieldConfidence(new java.util.LinkedHashMap<>());

        String merged = (safe(title) + "\n" + safe(content)).trim();
        String lower = merged.toLowerCase(Locale.ROOT);

        // 1) 诈骗手段
        if (!StringUtils.hasText(out.getFraudMethod().getScript())) {
            out.getFraudMethod().setScript(detectFraudMethodScript(merged, lower));
        }
        if (!StringUtils.hasText(out.getFraudMethod().getChannel())) {
            out.getFraudMethod().setChannel(detectChannel(lower));
        }
        if (!StringUtils.hasText(out.getFraudMethod().getPaymentChain())) {
            out.getFraudMethod().setPaymentChain(detectPaymentChain(lower));
        }

        // 2) 诈骗金额
        if (out.getAmount() == null || out.getAmount().compareTo(BigDecimal.ZERO) < 0) {
            out.setAmount(extractAmount(merged));
        }

        // 3) 诈骗对象
        if (!StringUtils.hasText(out.getVictimGroup().getOccupation())) {
            out.getVictimGroup().setOccupation(detectVictimObject(lower));
        }
        if (!StringUtils.hasText(out.getVictimGroup().getAgeRange())) {
            out.getVictimGroup().setAgeRange(detectAgeRange(merged));
        }
        if (!StringUtils.hasText(out.getVictimGroup().getRegionTag())) {
            out.getVictimGroup().setRegionTag(detectRegion(merged));
        }
        if (!StringUtils.hasText(out.getVictimGroup().getGender())) {
            out.getVictimGroup().setGender("未明确");
        }

        // 4) 诈骗地区
        if (!StringUtils.hasText(out.getFraudLocation().getProvince())) {
            out.getFraudLocation().setProvince(detectProvince(merged));
        }
        if (!StringUtils.hasText(out.getFraudLocation().getCity())) {
            out.getFraudLocation().setCity(detectCity(merged));
        }
        if (!StringUtils.hasText(out.getFraudLocation().getDistrict())) {
            out.getFraudLocation().setDistrict("未明确");
        }
        if (!StringUtils.hasText(out.getFraudLocation().getCountry())) {
            out.getFraudLocation().setCountry("中国");
        }
        if (!StringUtils.hasText(out.getFraudLocation().getScene())) {
            out.getFraudLocation().setScene(isOnlineScene(lower) ? "线上" : "线下");
        }

        if (!StringUtils.hasText(out.getCaseStatus())) {
            out.setCaseStatus("已发生或预警");
        }
        if (out.getCredibilityScore() == null) {
            out.setCredibilityScore(0.65d);
        }
        ensureFieldConfidence(out);
        return out;
    }

    private static void ensureFieldConfidence(FraudCaseExtraction out) {
        java.util.Map<String, Double> fc = out.getFieldConfidence();
        putIfMissing(fc, "victimGroup", 0.60d);
        putIfMissing(fc, "fraudMethod", 0.70d);
        putIfMissing(fc, "fraudTime", 0.55d);
        putIfMissing(fc, "fraudLocation", 0.60d);
        putIfMissing(fc, "amount", out.getAmount() != null && out.getAmount().compareTo(BigDecimal.ZERO) > 0 ? 0.75d : 0.35d);
        putIfMissing(fc, "caseStatus", 0.50d);
    }

    private static void putIfMissing(java.util.Map<String, Double> map, String key, double value) {
        if (!map.containsKey(key) || map.get(key) == null) {
            map.put(key, value);
        }
    }

    private static String detectFraudMethodScript(String text, String lower) {
        String[] hints = new String[]{
                "冒充公检法", "刷单返利", "虚假投资", "网络贷款", "冒充客服退款", "杀猪盘", "裸聊", "游戏交易", "中奖"
        };
        for (String h : hints) {
            if (lower.contains(h.toLowerCase(Locale.ROOT))) {
                return h;
            }
        }
        // 退化到标题摘要
        if (StringUtils.hasText(text)) {
            return text.length() > 40 ? text.substring(0, 40) : text;
        }
        return "未明确";
    }

    private static String detectChannel(String lower) {
        if (containsAny(lower, "微信", "qq", "抖音", "快手", "微博", "社交平台")) return "社交平台";
        if (containsAny(lower, "电话", "来电", "客服热线", "96110")) return "电话";
        if (containsAny(lower, "短信", "验证码")) return "短信";
        if (containsAny(lower, "app", "链接", "网站", "网页", "二维码")) return "互联网";
        return "未明确";
    }

    private static String detectPaymentChain(String lower) {
        if (containsAny(lower, "银行卡", "银行账户", "转账", "汇款")) return "银行卡转账";
        if (containsAny(lower, "支付宝")) return "支付宝转账";
        if (containsAny(lower, "微信支付", "微信转账")) return "微信转账";
        if (containsAny(lower, "充值", "提现")) return "平台充值/提现";
        return "未明确";
    }

    private static BigDecimal extractAmount(String text) {
        if (!StringUtils.hasText(text)) {
            return BigDecimal.ZERO;
        }
        Matcher m = AMOUNT_PATTERN.matcher(text);
        while (m.find()) {
            try {
                String n = m.group(1).replace(",", "");
                BigDecimal base = new BigDecimal(n);
                String unit = m.group(2);
                if ("千".equals(unit)) base = base.multiply(BigDecimal.valueOf(1_000L));
                if ("万".equals(unit)) base = base.multiply(BigDecimal.valueOf(10_000L));
                if ("亿".equals(unit)) base = base.multiply(BigDecimal.valueOf(100_000_000L));
                if (base.compareTo(BigDecimal.ZERO) > 0) {
                    return base;
                }
            } catch (Exception ignore) {
            }
        }
        return BigDecimal.ZERO;
    }

    private static String detectVictimObject(String lower) {
        if (containsAny(lower, "学生", "在校")) return "学生";
        if (containsAny(lower, "老年", "老人")) return "老年人";
        if (containsAny(lower, "财务", "会计", "出纳")) return "企业财务人员";
        if (containsAny(lower, "宝妈", "家庭主妇")) return "宝妈/家庭主妇";
        if (containsAny(lower, "商户", "老板", "企业")) return "商户/企业人员";
        if (containsAny(lower, "市民", "群众", "居民", "受害人")) return "普通群众";
        return "未明确";
    }

    private static String detectAgeRange(String text) {
        Matcher m = AGE_PATTERN.matcher(safe(text));
        if (m.find()) {
            return m.group(1) + "岁";
        }
        return "未明确";
    }

    private static String detectRegion(String text) {
        String province = detectProvince(text);
        if (StringUtils.hasText(province) && !"未明确".equals(province)) {
            String city = detectCity(text);
            if (StringUtils.hasText(city) && !"未明确".equals(city)) {
                return province + "-" + city;
            }
            return province;
        }
        String city = detectCity(text);
        return StringUtils.hasText(city) ? city : "未明确";
    }

    private static String detectProvince(String text) {
        String source = safe(text);
        for (String p : PROVINCES) {
            if (source.contains(p)) return p;
        }
        return "未明确";
    }

    private static String detectCity(String text) {
        Matcher m = CITY_PATTERN.matcher(safe(text));
        while (m.find()) {
            String city = m.group(1);
            if (city.contains("公安局") || city.contains("检察院") || city.contains("法院") || city.contains("反诈")) {
                continue;
            }
            return city;
        }
        return "未明确";
    }

    private static boolean isOnlineScene(String lower) {
        return containsAny(lower, "网络", "网上", "app", "链接", "二维码", "微信", "qq", "抖音", "平台");
    }

    private static boolean containsAny(String text, String... keys) {
        String src = safe(text);
        for (String k : keys) {
            if (src.contains(k)) return true;
        }
        return false;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') depth++;
            if (ch == '}') depth--;
            if (depth == 0) {
                return text.substring(start, i + 1).trim();
            }
        }
        return null;
    }
}
