package com.dony.api.requests;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.math.BigDecimal;

@ConfigurationProperties(prefix = "dony.requests")
public record RequestsConfig(
    int maxNegotiationRounds,
    int threadInactivityHours,
    int awaitingTripHours,
    int awaitingPaymentHours,
    int dateToleranceDefaultDays,
    int dateToleranceMaxDays,
    BigDecimal weightKgMin,
    BigDecimal weightKgMax,
    BigDecimal declaredValueMaxEur,
    int bodyMaxChars,
    int maxOpenRequestsPerSender,
    int maxOpenThreadsPerTraveler,
    int threadsPerMinuteRateLimit,
    String autoExpireCheckCron,
    int estimationCorridorRecentTrips,
    int estimationCacheTtlMinutes
) {}
