package com.dony.api.cancellation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Corps de POST /cancellations/bids/{id}/confirm-return : le code de retour à 6 chiffres. */
public record ConfirmReturnRequest(
        @NotBlank(message = "Le code de retour est obligatoire")
        @Pattern(regexp = "\\d{6}", message = "Le code doit contenir exactement 6 chiffres")
        String returnCode
) {}
