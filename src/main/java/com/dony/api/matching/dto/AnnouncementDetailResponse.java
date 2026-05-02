package com.dony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record AnnouncementDetailResponse(
        UUID id,
        UUID travelerId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
        @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
        AddressDto pickupAddress,
        AddressDto deliveryAddress,
        BigDecimal availableKg,
        BigDecimal pricePerKg,
        com.dony.api.matching.TransportMode transportMode,
        String status,
        long bidsCount,
        TravelerProfileDto traveler,
        String description,
        List<String> acceptedContentTypes,
        List<String> refusedTypes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
