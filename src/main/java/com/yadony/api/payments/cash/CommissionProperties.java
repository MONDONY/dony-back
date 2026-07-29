package com.yadony.api.payments.cash;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yadony.commission")
public record CommissionProperties(
        BigDecimal rate,
        BigDecimal minimumAmount,
        int noShowContestationHours
) {}
