package com.dony.api.payments.mobilemoney;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dony.mobilemoney")
public record MobileMoneyProperties(
        ProviderConfig wave,
        ProviderConfig orangeMoney,
        int linkExpiryMinutes
) {
    public record ProviderConfig(String apiKey, String apiUrl, String webhookSecret) {}
}
