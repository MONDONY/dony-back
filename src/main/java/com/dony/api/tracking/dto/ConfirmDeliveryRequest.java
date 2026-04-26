package com.dony.api.tracking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmDeliveryRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "Le code doit contenir exactement 6 chiffres")
        String confirmationCode
) {}
