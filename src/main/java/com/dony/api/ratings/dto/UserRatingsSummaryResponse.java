package com.dony.api.ratings.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record UserRatingsSummaryResponse(
        BigDecimal averageRating,
        int ratingCount,
        Map<Integer, Long> distribution,
        List<RatingItemResponse> ratings,
        int page,
        int totalPages
) {}
