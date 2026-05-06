package com.dony.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dony.stripe.connect")
public record StripeConnectProperties(
        String mcc,
        String productDescription,
        String businessUrl,
        String returnUrl,   // URL HTTPS backend → redirige vers deepLinkReturn
        String refreshUrl,  // URL HTTPS backend → redirige vers deepLinkRefresh
        String deepLinkReturn,
        String deepLinkRefresh
) {}
