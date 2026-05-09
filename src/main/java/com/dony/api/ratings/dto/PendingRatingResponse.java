package com.dony.api.ratings.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record PendingRatingResponse(
        UUID bidId,
        String otherPartyName,
        UUID otherPartyId,
        LocalDateTime deliveredAt,
        boolean isTravelerRating
) {}
