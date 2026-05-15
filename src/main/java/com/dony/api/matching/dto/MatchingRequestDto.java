package com.dony.api.matching.dto;

import java.util.UUID;

public record MatchingRequestDto(
        String id,
        String tripId,
        String tripCorridor,
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
