package com.dony.api.ratings.dto;

import java.time.LocalDateTime;

public record RatingItemResponse(
        int stars,
        String comment,
        LocalDateTime createdAt,
        boolean excluded
) {}
