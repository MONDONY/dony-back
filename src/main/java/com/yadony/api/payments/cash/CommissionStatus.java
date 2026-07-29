package com.yadony.api.payments.cash;

public enum CommissionStatus {
    PENDING,
    REQUIRES_3DS,
    CHARGED,
    FAILED,
    REFUNDED,
    REFUND_FAILED
}
