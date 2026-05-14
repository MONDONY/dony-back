package com.dony.api.matching.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record BidRequest(
        @NotNull(message = "Le poids est obligatoire")
        @DecimalMin(value = "0.1", message = "Le poids minimum est 0.1 kg")
        BigDecimal weightKg,

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

        String paymentMethod
) {}
