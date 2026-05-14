package com.dony.api.payments.cash.exception;

public class CommissionChargeFailedException extends RuntimeException {
    public CommissionChargeFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
