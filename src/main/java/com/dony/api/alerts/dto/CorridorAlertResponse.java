package com.dony.api.alerts.dto;

import com.dony.api.alerts.AlertDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CorridorAlertResponse(
        UUID id,
        String departureCity,
        String arrivalCity,
        String departureCountryCode,
        String arrivalCountryCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal minWeightKg,
        List<String> contentCategories,
        AlertDirection direction,
        boolean active,
        long matchCount,
        LocalDateTime createdAt,
        // ── Zone de remise optionnelle (SENDER_WANTS_TRIPS) ─────────────────
        BigDecimal centerLat,
        BigDecimal centerLng,
        Integer radiusKm,
        String centerLabel
) {
    /** Constructeur de compat (sans zone de remise) — délègue avec une zone nulle. */
    public CorridorAlertResponse(
            UUID id, String departureCity, String arrivalCity,
            String departureCountryCode, String arrivalCountryCode,
            LocalDate dateFrom, LocalDate dateTo, BigDecimal minWeightKg,
            List<String> contentCategories, AlertDirection direction,
            boolean active, long matchCount, LocalDateTime createdAt) {
        this(id, departureCity, arrivalCity, departureCountryCode, arrivalCountryCode,
                dateFrom, dateTo, minWeightKg, contentCategories, direction,
                active, matchCount, createdAt, null, null, null, null);
    }
}
