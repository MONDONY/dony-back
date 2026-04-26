package com.dony.api.ratings.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record RatingResponse(
        UUID id,
        UUID ratedUserId,
        UUID bidId,
        int stars,
        String comment,
        LocalDateTime createdAt
) {}
