package com.dony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminDisputeListItemResponse(
        UUID id,
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        String type,
        String status,
        boolean refundFrozen,
        BigDecimal declaredValueEur,
        LocalDateTime createdAt
) {}
