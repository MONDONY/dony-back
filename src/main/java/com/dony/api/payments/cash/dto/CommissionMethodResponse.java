package com.dony.api.payments.cash.dto;

import com.dony.api.payments.cash.ExpirationStatus;

public record CommissionMethodResponse(
        String brand,
        String last4,
        int expMonth,
        int expYear,
        ExpirationStatus expirationStatus
) {}
