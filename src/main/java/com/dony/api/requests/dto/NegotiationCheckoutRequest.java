package com.dony.api.requests.dto;

import com.dony.api.payments.cash.PaymentMethod;
import jakarta.validation.constraints.NotBlank;

/**
 * Sender confirms payment for an AWAITING_PAYMENT thread by passing the
 * Stripe PaymentIntent id (returned by their client-side confirmPayment call).
 * In Phase 1 (placeholder) we accept any string.
 *
 * <p>{@code paymentMethod} (optionnel) : le mode de paiement finalisé par
 * l'expéditeur parmi ceux acceptés par la demande. S'il est présent, il est
 * appliqué au thread (après validation) avant la décision cash/stripe. {@code
 * null} → on garde le mode déjà porté par le thread (choix du voyageur à la
 * liaison du trajet).
 */
public record NegotiationCheckoutRequest(
    @NotBlank String paymentIntentId,
    PaymentMethod paymentMethod
) {}
