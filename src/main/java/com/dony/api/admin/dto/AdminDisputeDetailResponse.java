package com.dony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminDisputeDetailResponse(
        UUID id,
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        String type,
        String status,
        boolean refundFrozen,
        String resolutionType,
        OffsetDateTime resolvedAt,
        String resolutionNote,
        BigDecimal declaredValueEur,
        UUID beneficiaryUserId,
        LocalDateTime createdAt
) {}
