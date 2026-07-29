package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.List;

public record TravelerStatsDto(
        BigDecimal monthlyRevenue,
        BigDecimal totalRevenue,
        long monthlyTrips,
        long monthlyParcelsDelivered,
        double acceptanceRate,
        BigDecimal averageRating,
        List<DestinationStat> topDestinations,
        // ── Vue d'ensemble tout-temps (cockpit) ──
        long totalTripsCompleted,
        long activeTrips,
        long totalParcelsDelivered,
        long parcelsInTransit,
        int ratingCount
) {
    public record DestinationStat(String from, String to, long count) {}
}
