package com.dony.api.requests.dto;

import com.dony.api.requests.entity.ParcelSize;
import com.dony.api.requests.entity.PackageRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record PackageRequestResponse(
    UUID id, UUID senderId,
    String departureCity, String arrivalCity,
    LocalDate desiredDate, int dateToleranceDays,
    BigDecimal weightKg, ParcelSize parcelSize, String contentCategory,
    String description, BigDecimal targetPriceEur, String photoUrl,
    String pickupNeighborhood, String deliveryNeighborhood,
    PackageRequestStatus status,
    LocalDateTime createdAt
) {}
