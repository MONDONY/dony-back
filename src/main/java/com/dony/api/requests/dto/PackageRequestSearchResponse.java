package com.dony.api.requests.dto;

import com.dony.api.matching.TransportMode;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.requests.entity.ParcelSize;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
    BigDecimal targetPriceEur, boolean negotiable, String photoUrl,
    String pickupNeighborhood, String deliveryNeighborhood,
    SenderPublicProfile sender,
    Set<PaymentMethod> acceptedPaymentMethods,
    /** Photos colis présignées (max 4, ordonnées). photoUrl = 1ère pour rétro-compat. */
    List<PackageRequestPhotoResponse> photos
) {
    public record SenderPublicProfile(UUID id, String displayName, double averageRating, int totalRatings, boolean kycVerified, String avatarUrl) {}
}
