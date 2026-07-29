package com.yadony.api.payments.cash;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yadony.cash-commission")
public record CashCommissionProperties(
        String orphanPiCleanupCron,
        int orphanPiTimeoutMinutes,
        String noShowTimeoutCron,
        int cardExpirationWarningDays
) {}
