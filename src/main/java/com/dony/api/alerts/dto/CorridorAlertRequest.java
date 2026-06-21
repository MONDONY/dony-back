package com.dony.api.alerts.dto;

import com.dony.api.alerts.AlertDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CorridorAlertRequest(
        @NotBlank String departureCity,
        String departureCountryCode,
        @NotBlank String arrivalCity,
        String arrivalCountryCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal minWeightKg,
        List<String> contentCategories,
        @NotNull AlertDirection direction,
        Boolean active,
        // ── Zone de remise optionnelle (SENDER_WANTS_TRIPS) ─────────────────
        BigDecimal centerLat,
        BigDecimal centerLng,
        Integer radiusKm,
        String centerLabel
) {
    /** Constructeur de compat (sans zone de remise) — délègue avec une zone nulle. */
    public CorridorAlertRequest(
            String departureCity, String departureCountryCode,
            String arrivalCity, String arrivalCountryCode,
            LocalDate dateFrom, LocalDate dateTo, BigDecimal minWeightKg,
            List<String> contentCategories, AlertDirection direction, Boolean active) {
        this(departureCity, departureCountryCode, arrivalCity, arrivalCountryCode,
                dateFrom, dateTo, minWeightKg, contentCategories, direction, active,
                null, null, null, null);
    }
}
