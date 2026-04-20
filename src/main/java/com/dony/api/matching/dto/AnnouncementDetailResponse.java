package com.dony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record AnnouncementDetailResponse(
        UUID id,
        UUID travelerId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        BigDecimal availableKg,
        BigDecimal pricePerKg,
        String status,
        long bidsCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
