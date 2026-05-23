package com.dony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
        @NotBlank(message = "Le nom de l'appareil est obligatoire")
        @Size(max = 255)
        String deviceName,

        @NotBlank
        @Pattern(regexp = "ios|android|web", message = "Plateforme invalide")
        String platform
) {}
