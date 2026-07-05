package com.dony.api.admin.dto;

import com.dony.api.payments.PaymentEntity;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminPaymentListItemResponse(
        UUID id,
        UUID bidId,
        String status,
        String method,
        long amountCents,
        long commissionCents,
        LocalDateTime createdAt
) {
    public static AdminPaymentListItemResponse from(PaymentEntity p) {
        return new AdminPaymentListItemResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                "STRIPE",
                p.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue(),
                p.getCommissionAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue(),
                p.getCreatedAt()
        );
    }
}
