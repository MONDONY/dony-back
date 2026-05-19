package com.dony.api.emailotp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailOtpVerifyRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 6, max = 6, message = "Le code doit faire 6 chiffres") String code
) {}
