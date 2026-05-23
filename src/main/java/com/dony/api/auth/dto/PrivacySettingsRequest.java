package com.dony.api.auth.dto;

import jakarta.validation.constraints.NotNull;

public record PrivacySettingsRequest(@NotNull Boolean contactKycOnly) {}
