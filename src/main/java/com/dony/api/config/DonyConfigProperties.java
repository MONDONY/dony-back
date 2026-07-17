package com.dony.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Dony application configuration properties (prefix "dony").
 * Note: dony.stripe.* and dony.commission-rate (legacy flat key) are
 * intentionally consumed via @Value in PaymentService for now.
 */
@ConfigurationProperties(prefix = "dony")
public record DonyConfigProperties(
    Commission commission,
    Limits limits,
    Urgency urgency
) {
    public record Commission(BigDecimal rate) {}

    public record Urgency(Integer thresholdDays) {}

    public record Limits(NonPro nonPro, Drafts drafts) {
        public record NonPro(int monthlyAnnouncements) {}
        public record Drafts(Integer max, Integer maxPro) {}

        public int monthlyAnnouncements() {
            return nonPro != null ? nonPro.monthlyAnnouncements() : 2;
        }

        public int maxDrafts() {
            return drafts != null && drafts.max() != null ? drafts.max() : 1;
        }

        public int maxDraftsPro() {
            return drafts != null && drafts.maxPro() != null ? drafts.maxPro() : 10;
        }
    }
}
