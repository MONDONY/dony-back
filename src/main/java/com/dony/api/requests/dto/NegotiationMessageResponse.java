package com.dony.api.requests.dto;

import com.dony.api.requests.entity.NegotiationMessageKind;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record NegotiationMessageResponse(
    UUID id, UUID threadId, UUID fromUserId,
    NegotiationMessageKind kind, BigDecimal proposedPriceEur, String body,
    LocalDateTime createdAt
) {}
