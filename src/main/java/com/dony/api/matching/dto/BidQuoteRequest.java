package com.dony.api.matching.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Devis avant création du bid : calcule le total exact avec promo éventuel.
 * Endpoint : POST /bids/quote (ROLE_SENDER)
 */
public record BidQuoteRequest(
        @NotNull(message = "L'annonce est obligatoire")
        UUID announcementId,

        @DecimalMin(value = "0.1", message = "Poids minimum 0.1 kg")
        BigDecimal weightKg,

        /** Code promo optionnel (insensible à la casse). */
        String promoCode
) {}
