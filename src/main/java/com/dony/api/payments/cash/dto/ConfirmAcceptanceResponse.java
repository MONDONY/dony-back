package com.dony.api.payments.cash.dto;

public record ConfirmAcceptanceResponse(boolean accepted, String error) {
    public static ConfirmAcceptanceResponse ok() {
        return new ConfirmAcceptanceResponse(true, null);
    }

    public static ConfirmAcceptanceResponse fail(String error) {
        return new ConfirmAcceptanceResponse(false, error);
    }
}
