package com.dony.api.requests.dto;

import com.dony.api.matching.TransportMode;
import com.dony.api.requests.entity.ParcelSize;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PackageRequestSearchResponse(
    UUID id,
    String departureCity, String arrivalCity,
    BigDecimal departureLat, BigDecimal departureLng,
    BigDecimal arrivalLat, BigDecimal arrivalLng,
    LocalDate desiredDate, int dateToleranceDays,
    BigDecimal weightKg, ParcelSize parcelSize,
    TransportMode transportMode,
    String contentCategory,
    BigDecimal targetPriceEur, String photoUrl,
    String pickupNeighborhood, String deliveryNeighborhood,
    SenderPublicProfile sender
) {
    public record SenderPublicProfile(UUID id, String displayName, double averageRating, int totalRatings, boolean kycVerified) {}
}
