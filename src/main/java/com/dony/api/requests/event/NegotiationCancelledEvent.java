package com.dony.api.requests.event;

import java.util.UUID;

/**
 * A participant (sender or traveler) ended the negotiation before payment
 * (thread status OPEN, AWAITING_TRIP or AWAITING_PAYMENT). Notify the other
 * party that the negotiation is over.
 */
public record NegotiationCancelledEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID byUserId,
    UUID toUserId,
    String byName
) {}
