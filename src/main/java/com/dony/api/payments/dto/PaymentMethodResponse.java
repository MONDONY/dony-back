package com.dony.api.payments.dto;

/** Carte enregistrée d'un customer Stripe, exposée à la DonyPaymentSheet. */
public record PaymentMethodResponse(
        String id,
        String brand,
        String last4,
        int expMonth,
        int expYear) {
}
