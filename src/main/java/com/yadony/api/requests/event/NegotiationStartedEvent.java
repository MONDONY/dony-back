package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

public record NegotiationStartedEvent(
    UUID threadId, UUID packageRequestId,
    UUID senderId, UUID travelerId,
    BigDecimal proposedPriceEur
) {}
