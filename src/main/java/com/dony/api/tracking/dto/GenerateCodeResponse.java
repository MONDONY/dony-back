package com.dony.api.tracking.dto;

import java.time.LocalDateTime;

public record GenerateCodeResponse(
        String message,
        String recipientPhone,
        LocalDateTime expiresAt
) {}
