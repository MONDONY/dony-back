package com.dony.api.common.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "dony.stripe.webhook")
public record StripeWebhookProperties(
        Duration pollInterval,
        int batchSize,
        int maxRetries,
        Duration retryBackoffBase,
        boolean schedulerEnabled
) {
    public StripeWebhookProperties {
        if (pollInterval == null) pollInterval = Duration.ofSeconds(10);
        if (batchSize == 0) batchSize = 50;
        if (maxRetries == 0) maxRetries = 8;
        if (retryBackoffBase == null) retryBackoffBase = Duration.ofSeconds(30);
    }
}
