package com.dony.api.alerts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CorridorAlertResponse(
        UUID id,
        String departureCity,
        String arrivalCity,
        String departureCountryCode,
        String arrivalCountryCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal minWeightKg,
        List<String> contentCategories,
        boolean active,
        long matchCount,
        LocalDateTime createdAt
) {}
