package com.yadony.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Yadony application configuration properties (prefix "yadony").
 * Note: yadony.stripe.* and yadony.commission-rate (legacy flat key) are
 * intentionally consumed via @Value in PaymentService for now.
 */
@ConfigurationProperties(prefix = "yadony")
public record YadonyConfigProperties(
    Commission commission,
    Limits limits,
    Urgency urgency,
    Reimbursement reimbursement
) {
    public YadonyConfigProperties {
        if (urgency == null) {
            urgency = new Urgency(null);
        }
        if (reimbursement == null) {
            reimbursement = new Reimbursement(null);
        }
    }

    public record Commission(BigDecimal rate) {}

    public record Urgency(Integer thresholdDays) {
        public Urgency {
            if (thresholdDays == null) {
                thresholdDays = 3;
            }
        }
    }

    /** Plafond de remboursement Yadony en cas de perte de colis (défaut 50 €). */
    public record Reimbursement(BigDecimal maxAmountEur) {
        public Reimbursement {
            if (maxAmountEur == null) {
                maxAmountEur = new BigDecimal("50");
            }
        }
    }

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
