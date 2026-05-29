package com.dony.api.payments.mobilemoney.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record MobileMoneyStatusResponse(
        UUID id,
        String status,
        String paymentLink,
        LocalDateTime expiresAt,
        BigDecimal amount,
        String currency,
        String failureReason
) {}
