package com.dony.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dony application configuration properties (prefix "dony").
 * Note: dony.stripe.* and dony.commission-rate (legacy flat key) are
 * intentionally consumed via @Value in PaymentService for now.
 */
@ConfigurationProperties(prefix = "dony")
public record DonyConfigProperties(
    Commission commission,
    Limits limits,
    List<String> contentCategories
) {
    public record Commission(BigDecimal rate) {}

    public record Limits(NonPro nonPro) {
        public record NonPro(int monthlyAnnouncements) {}

        public int monthlyAnnouncements() {
            return nonPro != null ? nonPro.monthlyAnnouncements() : 2;
        }
    }
}
