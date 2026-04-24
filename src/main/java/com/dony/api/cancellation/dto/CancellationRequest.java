package com.dony.api.cancellation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CancellationRequest(
        @NotNull UUID announcementId,
        @NotBlank String reason
) {}
