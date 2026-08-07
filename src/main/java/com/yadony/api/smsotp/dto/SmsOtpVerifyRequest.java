package com.yadony.api.smsotp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SmsOtpVerifyRequest(
    @NotBlank
    @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "Le numéro doit être au format E.164 (ex: +33612345678)")
    String phoneNumber,

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "Le code doit contenir exactement 6 chiffres")
    String code
) {}
