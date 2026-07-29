package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * The waiting party nudged the party who must act (traveler in AWAITING_TRIP,
 * or the recipient of the last message in OPEN) to remind them to respond.
 */
public record NegotiationNudgeSentEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID fromUserId,
    UUID toUserId,
    String fromUserName
) {}
