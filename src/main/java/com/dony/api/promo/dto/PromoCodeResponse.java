package com.dony.api.promo.dto;

import com.dony.api.promo.PromoCodeStatus;
import com.dony.api.promo.PromoCodeTarget;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PromoCodeResponse(
        UUID id,
        String code,
        BigDecimal rate,
        PromoCodeTarget target,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        Integer maxRedemptions,
        int perUserLimit,
        int redeemedCount,
        PromoCodeStatus status,
        LocalDateTime createdAt
) {}
