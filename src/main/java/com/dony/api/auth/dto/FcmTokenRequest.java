package com.dony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FcmTokenRequest(
        @NotBlank(message = "Le token FCM est obligatoire")
        @Size(max = 512, message = "Token FCM invalide")
        String fcmToken,

        @Size(max = 128)
        String deviceId,

        @Size(max = 255)
        String deviceName,

        @Pattern(regexp = "ios|android")
        String platform
) {}
