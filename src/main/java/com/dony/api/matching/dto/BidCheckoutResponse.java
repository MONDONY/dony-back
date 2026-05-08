package com.dony.api.matching.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record BidCheckoutResponse(
        UUID bidId,
        String clientSecret,
        String publishableKey,
        LocalDateTime expiresAt
) {}
