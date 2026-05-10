package com.dony.api.payments;

import java.util.UUID;

/**
 * Published when a Stripe PaymentIntent for a negotiation thread reaches
 * `amount_capturable_updated` (escrow active). Listened by the marketplace
 * to finalize the thread (→ ACCEPTED + auto-reject competing threads).
 */
public record NegotiationPaymentAuthorizedEvent(
    UUID threadId,
    UUID senderId,
    String paymentIntentId
) {}
