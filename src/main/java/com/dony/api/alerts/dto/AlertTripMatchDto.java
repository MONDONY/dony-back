package com.dony.api.alerts.dto;

import com.dony.api.matching.TransportMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AlertTripMatchDto(
        UUID announcementId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        UUID travelerId,
        String travelerName,
        String travelerInitials,
        double travelerRating,
        BigDecimal availableKg,
        BigDecimal pricePerKg,
        TransportMode transportMode,
        String photoUrl
) {}
