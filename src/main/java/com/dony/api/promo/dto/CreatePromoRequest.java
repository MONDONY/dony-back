package com.dony.api.promo.dto;

import com.dony.api.promo.PromoCodeTarget;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreatePromoRequest(
        @NotBlank(message = "Le code promo est obligatoire")
        @Size(max = 40)
        String code,

        @NotNull(message = "Le taux est obligatoire")
        @DecimalMin("0.0")
        @DecimalMax("0.999")
        BigDecimal rate,

        PromoCodeTarget target,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        Integer maxRedemptions,
        Integer perUserLimit
) {}
