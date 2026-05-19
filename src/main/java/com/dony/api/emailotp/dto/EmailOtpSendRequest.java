package com.dony.api.emailotp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailOtpSendRequest(
    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "Format email invalide")
    String email
) {}
