package com.dony.api.triptemplate.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record TripTemplateDto(
    UUID id,
    String label,
    String emoji,
    String departureCity,
    Double departureLat,
    Double departureLng,
    String arrivalCity,
    Double arrivalLat,
    Double arrivalLng,
    String transportMode,
    String capacityUnit,
    Integer availableKg,
    Double pricePerKg,
    List<String> acceptedCategories,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
