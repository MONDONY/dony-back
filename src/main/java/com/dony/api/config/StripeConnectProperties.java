package com.dony.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dony.stripe.connect")
public record StripeConnectProperties(
        String mcc,
        String productDescription,
        String businessUrl,
        String returnUrl,
        String refreshUrl
) {}
