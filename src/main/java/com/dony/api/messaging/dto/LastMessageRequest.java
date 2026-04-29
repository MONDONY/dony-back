package com.dony.api.messaging.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LastMessageRequest(
        @NotBlank @Size(max = 80) String preview
) {}
