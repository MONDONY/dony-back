package com.dony.api.matching.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record BidCheckoutRequest(
        @NotNull UUID announcementId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal weightKg,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal declaredValueEur,
        @Size(max = 1000) String description,
        @Size(max = 50) String contentCategory,
        @Size(max = 200) String recipientName,
        @Size(max = 30) String recipientPhone,
        @AssertTrue(message = "Le disclaimer doit être signé") Boolean disclaimerSigned
) {}
