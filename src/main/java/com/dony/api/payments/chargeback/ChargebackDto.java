package com.dony.api.payments.chargeback;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public record ChargebackDto(
        UUID id,
        String stripeDisputeId,
        String stripeChargeId,
        UUID paymentId,
        UUID bidId,
        long amount,
        String currency,
        String reason,
        ChargebackStatus status,
        String outcome,
        Instant openedAt,
        Instant resolvedAt,
        LocalDateTime createdAt
) {
    static ChargebackDto from(ChargebackEntity e) {
        return new ChargebackDto(
                e.getId(), e.getStripeDisputeId(), e.getStripeChargeId(),
                e.getPaymentId(), e.getBidId(), e.getAmount(), e.getCurrency(),
                e.getReason(), e.getStatus(), e.getOutcome(),
                e.getOpenedAt(), e.getResolvedAt(), e.getCreatedAt()
        );
    }
}
