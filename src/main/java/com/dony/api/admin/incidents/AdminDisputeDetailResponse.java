package com.dony.api.admin.incidents;

import com.dony.api.disputes.DisputeEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminDisputeDetailResponse(
        UUID id,
        UUID bidId,
        String type,
        String status,
        String senderName,
        String travelerName,
        boolean refundFrozen,
        LocalDateTime createdAt,
        String resolution,
        LocalDateTime resolvedAt,
        String resolutionNote,
        BigDecimal declaredValueEur,
        UUID beneficiaryUserId
) {
    public static AdminDisputeDetailResponse from(DisputeEntity d, String senderName, String travelerName, BigDecimal declaredValueEur) {
        return new AdminDisputeDetailResponse(
                d.getId(), d.getBidId(), d.getType(), d.getStatus(),
                senderName, travelerName, d.isRefundFrozen(), d.getCreatedAt(),
                d.getResolution(), d.getResolvedAt(), d.getResolutionNote(),
                declaredValueEur, d.getBeneficiaryUserId()
        );
    }
}
