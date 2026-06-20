package com.dony.api.alerts.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CorridorAlertUpdateRequest(
        @NotBlank String departureCity,
        String departureCountryCode,
        @NotBlank String arrivalCity,
        String arrivalCountryCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal minWeightKg,
        List<String> contentCategories,
        Boolean active
) {
    public CorridorAlertRequest toCorridorAlertRequest() {
        return new CorridorAlertRequest(
                departureCity, departureCountryCode, arrivalCity, arrivalCountryCode,
                dateFrom, dateTo, minWeightKg, contentCategories);
    }
}
