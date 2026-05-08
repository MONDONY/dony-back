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
    Commission commission
) {
    public record Commission(BigDecimal rate) {}
}
