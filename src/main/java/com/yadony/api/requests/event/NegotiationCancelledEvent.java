package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * A participant (sender or traveler) ended the negotiation before payment
 * (thread status OPEN, AWAITING_TRIP or AWAITING_PAYMENT). Notify the other
 * party that the negotiation is over.
 *
 * <p>{@code releaseEscrow} is {@code true} only when the thread was
 * AWAITING_PAYMENT — an in-flight Stripe card hold may exist and MUST be
 * cancelled. Per CLAUDE.md rule #18 that Stripe side-effect runs in a payments
 * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}
 * listener, so the hold is only released once the cancel transaction actually
 * commits (a rollback fires no AFTER_COMMIT → no financial leak).
 */
public record NegotiationCancelledEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID byUserId,
    UUID toUserId,
    String byName,
    boolean releaseEscrow
) {}
