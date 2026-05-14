package com.dony.api.payments.cash;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dony.cash-commission")
public record CashCommissionProperties(
        String orphanPiCleanupCron,
        int orphanPiTimeoutMinutes,
        String noShowTimeoutCron,
        int cardExpirationWarningDays
) {}
