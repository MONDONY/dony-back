package com.dony.api.requests.dto;

import java.util.UUID;

public record LinkedTripSummary(
    UUID announcementId,
    String departureCity,
    String arrivalCity,
    String departureDate,       // "2026-06-12"
    String departureTime,       // "14:30" — null si absent
    String transportMode,       // "PLANE" | "TRAIN" | "CAR"
    String pickupAddressLabel,
    String deliveryAddressLabel,
    int availableKg,
    String description
) {}
