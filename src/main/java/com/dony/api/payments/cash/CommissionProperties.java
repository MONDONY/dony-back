package com.dony.api.payments.cash;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dony.commission")
public record CommissionProperties(
        BigDecimal rate,
        BigDecimal minimumAmount,
        int noShowContestationHours
) {}
