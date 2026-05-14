package com.dony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

public record NegotiationCounterPostedEvent(
    UUID threadId, UUID messageId,
    UUID fromUserId, UUID toUserId,
    BigDecimal newPriceEur, int roundsCount
) {}
