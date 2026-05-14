package com.dony.api.addressbook.favorite.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record FavoriteTravelerDto(
        UUID id,
        UUID travelerId,
        String displayName,
        BigDecimal averageRating,
        String notes,
        LocalDateTime createdAt
) {}
