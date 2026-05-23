package com.dony.api.auth.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BlockedUserDto(
        UUID userId,
        String displayName,
        OffsetDateTime blockedAt
) {}
