package com.dony.api.matching.dto;

import java.math.BigDecimal;
import java.util.List;

public record TravelerStatsDto(
        BigDecimal monthlyRevenue,
        BigDecimal totalRevenue,
        long monthlyTrips,
        long monthlyParcelsDelivered,
        double acceptanceRate,
        BigDecimal averageRating,
        List<DestinationStat> topDestinations
) {
    public record DestinationStat(String from, String to, long count) {}
}
