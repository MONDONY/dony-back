package com.dony.api.matching.dto;

public record MatchingRequestDto(
        String id,
        String tripId,
        String tripCorridor,
        String tripDepartureDate,
        double tripAvailableKg,
        String senderName,
        String senderInitials,
        double senderRating,
        int senderTotalSent,
        double weightKg,
        String contentType,
        double budgetPerKg,
        String messageExcerpt,
        int matchScore,
        String requestedAt
) {}
