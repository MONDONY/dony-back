package com.dony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record AnnouncementSearchResponse(
        UUID id,
        UUID travelerId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        BigDecimal availableKg,
        BigDecimal pricePerKg,
        String status,
        long bidsCount,
        TravelerProfileDto traveler,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
