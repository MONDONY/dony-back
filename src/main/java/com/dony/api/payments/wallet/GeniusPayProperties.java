package com.dony.api.payments.wallet;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dony.geniuspay")
public record GeniusPayProperties(
        String apiKey,
        String apiSecret,
        String baseUrl,
        String webhookSecret
) {}
