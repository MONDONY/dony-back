package com.yadony.api.payments.dto;

/** Carte enregistrée d'un customer Stripe, exposée à la YadonyPaymentSheet. */
public record PaymentMethodResponse(
        String id,
        String brand,
        String last4,
        int expMonth,
        int expYear) {
}
