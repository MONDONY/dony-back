package com.dony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Traveler linked a trip. The sender must now pay (Stripe escrow) to finalize.
 */
public record NegotiationAwaitingPaymentEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID senderId,
    UUID travelerId,
    BigDecimal agreedPriceEur,
    UUID travelerAnnouncementId
) {}
