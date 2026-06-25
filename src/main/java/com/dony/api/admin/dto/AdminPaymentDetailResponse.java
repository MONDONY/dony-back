package com.dony.api.admin.dto;

import com.dony.api.payments.PaymentEntity;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminPaymentDetailResponse(
        UUID id,
        UUID bidId,
        String status,
        String method,
        long amountCents,
        long commissionCents,
        LocalDateTime createdAt,
        long refundedCents,
        String stripePaymentIntentId,
        LocalDateTime escrowReleasedAt,
        boolean disputed
) {
    public static AdminPaymentDetailResponse from(PaymentEntity p) {
        return new AdminPaymentDetailResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                "STRIPE",
                p.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue(),
                p.getCommissionAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue(),
                p.getCreatedAt(),
                p.getRefundedAmount() != null
                        ? p.getRefundedAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue()
                        : 0L,
                p.getStripePaymentIntentId(),
                p.getEscrowReleasedAt(),
                p.isDisputed()
        );
    }
}
