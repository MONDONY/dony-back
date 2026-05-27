package com.dony.api.triptemplate.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    boolean cashAccepted,
    @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
