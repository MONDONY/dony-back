package com.dony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public record AnnouncementResponse(
        UUID id,
        UUID travelerId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
        @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
        String departureLocation,
        String arrivalLocation,
        BigDecimal availableKg,
        BigDecimal pricePerKg,
        String status,
        long bidsCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
