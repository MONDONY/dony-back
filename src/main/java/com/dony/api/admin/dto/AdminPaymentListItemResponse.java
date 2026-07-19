package com.dony.api.admin.dto;

import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.common.money.MinorUnits;
import com.dony.api.common.money.Money;
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
    public static AdminPaymentListItemResponse from(PaymentEntity p, CurrencyRegistry registry) {
        return new AdminPaymentListItemResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                "STRIPE",
                MinorUnits.toMinor(new Money(p.getAmount(), "EUR"), registry),
                MinorUnits.toMinor(new Money(p.getCommissionAmount(), "EUR"), registry),
                p.getCreatedAt()
        );
    }
}
