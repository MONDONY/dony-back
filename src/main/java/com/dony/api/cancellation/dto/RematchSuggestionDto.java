package com.dony.api.cancellation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RematchSuggestionDto(
        UUID suggestionId,
        UUID announcementId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        BigDecimal availableKg,
        BigDecimal pricePerKg
) {}
