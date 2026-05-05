package com.dony.api.payments.exceptions;

import java.util.UUID;

public class TravelerNotEligibleForPaymentException extends RuntimeException {
    private final UUID travelerId;

    public TravelerNotEligibleForPaymentException(UUID travelerId) {
        super("Traveler " + travelerId + " is not eligible for payment: Stripe Connect onboarding not complete");
        this.travelerId = travelerId;
    }

    public UUID getTravelerId() { return travelerId; }
}
