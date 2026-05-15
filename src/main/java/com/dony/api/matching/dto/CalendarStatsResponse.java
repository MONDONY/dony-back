package com.dony.api.matching.dto;

public record CalendarStatsResponse(
        long activeTripsCount,
        long totalTripsThisMonth
) {}
