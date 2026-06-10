package com.dony.api.matching.dto;

import java.math.BigDecimal;

public record TripsSummaryDto(
        long activeTrips,
        BigDecimal kgSoldThisMonth,
        BigDecimal revenueThisMonth
) {}
