package com.yadony.api.matching.dto;

public record CalendarStatsResponse(
        long activeTripsCount,
        long totalTripsThisMonth
) {}
