package com.dony.api.admin.dto;

import com.dony.api.payments.chargeback.ChargebackEntity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record AdminChargebackResponse(
        UUID id,
        UUID bidId,
        long amountCents,
        String reason,
        String status,
        LocalDateTime openedAt
) {
    public static AdminChargebackResponse from(ChargebackEntity c) {
        return new AdminChargebackResponse(
                c.getId(),
                c.getBidId(),
                c.getAmount(),
                c.getReason(),
                c.getStatus().name(),
                c.getOpenedAt() != null
                        ? LocalDateTime.ofInstant(c.getOpenedAt(), ZoneOffset.UTC)
                        : null
        );
    }
}
