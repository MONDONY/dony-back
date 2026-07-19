package com.dony.api.admin.dto;

import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.common.money.MinorUnits;
import com.dony.api.common.money.Money;
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
    public static AdminPaymentDetailResponse from(PaymentEntity p, CurrencyRegistry registry) {
        return new AdminPaymentDetailResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                "STRIPE",
                MinorUnits.toMinor(new Money(p.getAmount(), "EUR"), registry),
                MinorUnits.toMinor(new Money(p.getCommissionAmount(), "EUR"), registry),
                p.getCreatedAt(),
                p.getRefundedAmount() != null
                        ? MinorUnits.toMinor(new Money(p.getRefundedAmount(), "EUR"), registry)
                        : 0L,
                p.getStripePaymentIntentId(),
                p.getEscrowReleasedAt(),
                p.isDisputed()
        );
    }
}
