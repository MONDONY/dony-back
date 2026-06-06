package com.dony.api.requests.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Body for POST /negotiations/trip/{announcementId}/open-surplus.
 * surplusKg: kg supplémentaires ouverts au public (≥ 1).
 * pricePerKg: prix libre du surplus (> 0).
 */
public record OpenSurplusRequest(
        @NotNull @DecimalMin("1.0") BigDecimal surplusKg,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal pricePerKg
) {}
