package com.dony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

public record PackageRequestAcceptedEvent(
    UUID threadId, UUID packageRequestId,
    UUID senderId, UUID travelerId,
    BigDecimal agreedPriceEur,
    UUID travelerAnnouncementId
) {}
