package com.yadony.api.disputes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Vue « Mes litiges » côté utilisateur. Le contexte colis (villes, date, poids,
 * autre partie) est null si le bid ou l'annonce a été soft-deleted.
 */
public record DisputeResponse(
        UUID id,
        UUID bidId,
        String type,
        String status,
        boolean refundFrozen,
        LocalDateTime createdAt,
        String myRole,             // "SENDER" | "TRAVELER"
        String otherPartyName,
        String departureCity,
        String arrivalCity,
        String departureCountryCode,
        String arrivalCountryCode,
        LocalDate tripDate,
        BigDecimal weightKg,
        String resolutionType,
        OffsetDateTime resolvedAt,
        String resolutionNote,
        Long guaranteeAmountCents,
        boolean isBeneficiary
) {}
