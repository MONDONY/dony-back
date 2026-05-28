package com.dony.api.disputes.dto;

import com.dony.api.disputes.DisputeEntity;

import java.time.LocalDateTime;
import java.util.UUID;

public record DisputeResponse(
        UUID id,
        UUID bidId,
        String type,
        String status,
        boolean refundFrozen,
        LocalDateTime createdAt
) {
    public static DisputeResponse from(DisputeEntity e) {
        return new DisputeResponse(
                e.getId(),
                e.getBidId(),
                e.getType(),
                e.getStatus(),
                e.isRefundFrozen(),
                e.getCreatedAt()
        );
    }
}
