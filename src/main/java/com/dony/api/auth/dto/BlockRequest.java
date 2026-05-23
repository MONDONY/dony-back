package com.dony.api.auth.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record BlockRequest(
        @NotNull(message = "blockedUserId est obligatoire")
        UUID blockedUserId
) {}
