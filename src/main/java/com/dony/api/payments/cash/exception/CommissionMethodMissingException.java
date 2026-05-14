package com.dony.api.payments.cash.exception;

public class CommissionMethodMissingException extends RuntimeException {
    public CommissionMethodMissingException() {
        super("Vous devez enregistrer une carte avant d'accepter un bid cash.");
    }
}
