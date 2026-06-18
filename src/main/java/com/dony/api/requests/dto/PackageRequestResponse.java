package com.dony.api.requests.dto;

import com.dony.api.matching.TransportMode;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.requests.entity.ParcelSize;
import com.dony.api.requests.entity.PackageRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PackageRequestResponse(
    UUID id, UUID senderId,
    String departureCity, String arrivalCity,
    LocalDate desiredDate, int dateToleranceDays,
    BigDecimal weightKg, ParcelSize parcelSize,
    TransportMode transportMode,
    String contentCategory,
    String description, BigDecimal targetPriceEur, String photoUrl,
    String pickupNeighborhood, String deliveryNeighborhood,
    PackageRequestStatus status,
    LocalDateTime createdAt,
    // Modèle B : champs négociation
    boolean negotiable,
    Set<PaymentMethod> acceptedPaymentMethods,
    /** Prix brut (commission incluse) affiché à l'expéditeur — null si pas de budget. */
    BigDecimal grossPriceEur,
    /** Photos colis présignées (max 4, ordonnées). photoUrl = 1ère pour rétro-compat. */
    List<PackageRequestPhotoResponse> photos,
    /** Thread de négociation ACTIF du voyageur appelant sur cette demande (null s'il n'en a pas). */
    UUID viewerThreadId,
    /** Statut de ce thread (OPEN, AWAITING_TRIP, AWAITING_PAYMENT, ACCEPTED) — null sinon. */
    String viewerThreadStatus
) {}
