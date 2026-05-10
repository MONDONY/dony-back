package com.dony.api.requests.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Sender confirms payment for an AWAITING_PAYMENT thread by passing the
 * Stripe PaymentIntent id (returned by their client-side confirmPayment call).
 * In Phase 1 (placeholder) we accept any string.
 */
public record NegotiationCheckoutRequest(
    @NotBlank String paymentIntentId
) {}
