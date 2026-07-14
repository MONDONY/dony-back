package com.dony.api.payments.dto;

/** Clé éphémère Stripe permettant à la PaymentSheet native de lire les cartes du customer. */
public record EphemeralKeyResponse(String ephemeralKeySecret, String customerId) {
}
