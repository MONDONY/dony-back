package com.dony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FcmTokenRequest(
        @NotBlank(message = "Le token FCM est obligatoire")
        @Size(max = 512, message = "Token FCM invalide")
        String fcmToken
) {}
