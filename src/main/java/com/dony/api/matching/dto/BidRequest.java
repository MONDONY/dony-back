package com.dony.api.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

public record BidRequest(
        @DecimalMin(value = "0.1", message = "Le poids minimum est 0.1 kg")
        BigDecimal weightKg,  // nullable désormais (GRID mode = pas de poids)

        @NotNull(message = "La valeur déclarée est obligatoire")
        @DecimalMin(value = "0.01", message = "La valeur déclarée doit être positive")
        @DecimalMax(value = "500.00", message = "Valeur maximum : 500 €")
        BigDecimal declaredValueEur,

        @NotBlank(message = "La description du contenu est obligatoire")
        String description,

        @NotBlank(message = "La catégorie est obligatoire")
        String contentCategory,

        @NotBlank(message = "Le prénom et nom du destinataire sont obligatoires")
        String recipientName,

        @NotBlank(message = "Le numéro de téléphone du destinataire est obligatoire")
        String recipientPhone,

        @NotNull(message = "Le disclaimer légal doit être accepté")
        Boolean disclaimerSigned,

        String paymentMethod,

        // Mobile Money fields — nullable; required only when paymentMethod is WAVE or ORANGE_MONEY
        @Pattern(regexp = "^\\+?[1-9]\\d{6,19}$", message = "Numéro de téléphone invalide (format E.164 attendu)")
        String phoneNumber,

        String countryCode,

        /** Code promo optionnel (insensible à la casse) — validé et racheté au paiement. */
        String promoCode,

        @Valid List<BidGridItemRequest> gridItems  // peut être null ou vide — doit rester en DERNIER
) {}
