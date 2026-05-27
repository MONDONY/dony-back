package com.dony.api.matching.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record TripRecurrenceDto(
        UUID id,
        UUID sourceTemplateId,
        String departureCity,
        String arrivalCity,
        String transportMode,
        String capacityUnit,
        Double availableKg,
        Double pricePerKg,
        List<String> acceptedCategories,
        AddressDto pickupAddress,
        AddressDto deliveryAddress,
        LocalTime departureTime,
        LocalTime arrivalTime,
        boolean cashAccepted,
        String weekdays,
        Integer horizonDays,
        boolean active,
        LocalDate lastGeneratedDate,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
