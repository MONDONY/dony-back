package com.dony.api.promo.dto;

import com.dony.api.promo.PromoCodeTarget;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mise à jour partielle d'un code promo.
 * Tous les champs sont nullable — seuls les champs non-null sont appliqués (patch sémantique).
 */
public record UpdatePromoRequest(
        @DecimalMin("0.0")
        @DecimalMax("0.999")
        BigDecimal rate,

        PromoCodeTarget target,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        Integer maxRedemptions,

        @Min(1)
        Integer perUserLimit
) {}
