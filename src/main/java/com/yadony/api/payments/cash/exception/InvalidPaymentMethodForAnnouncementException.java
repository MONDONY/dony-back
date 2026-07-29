package com.yadony.api.payments.cash.exception;

public class InvalidPaymentMethodForAnnouncementException extends RuntimeException {
    public InvalidPaymentMethodForAnnouncementException(String message) {
        super(message);
    }
}
