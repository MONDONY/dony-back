package com.dony.api.admin.incidents;

import com.dony.api.disputes.DisputeEntity;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminDisputeSummary(
        UUID id,
        UUID bidId,
        String type,
        String status,
        String senderName,
        String travelerName,
        boolean refundFrozen,
        LocalDateTime createdAt
) {
    public static AdminDisputeSummary from(DisputeEntity d, String senderName, String travelerName) {
        return new AdminDisputeSummary(
                d.getId(), d.getBidId(), d.getType(), d.getStatus(),
                senderName, travelerName, d.isRefundFrozen(), d.getCreatedAt()
        );
    }
}
