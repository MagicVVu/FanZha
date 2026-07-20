package com.magicvvu.fanzha.backend.service.fraud.deepseek;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
public class FraudCaseExtraction {
    private VictimGroup victimGroup;
    private FraudMethod fraudMethod;
    private FraudTime fraudTime;
    private FraudLocation fraudLocation;
    private BigDecimal amount;
    private String caseStatus;
    private Double credibilityScore;
    private Map<String, Double> fieldConfidence;

    @Data
    public static class VictimGroup {
        private String ageRange;
        private String occupation;
        private String gender;
        private String regionTag;
    }

    @Data
    public static class FraudMethod {
        private String script;
        private String channel;
        private String paymentChain;
    }

    @Data
    public static class FraudTime {
        private String timeRange;
        private Integer durationDays;
    }

    @Data
    public static class FraudLocation {
        private String country;
        private String province;
        private String city;
        private String district;
        private String scene;
    }
}
