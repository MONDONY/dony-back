package com.dony.api.requests.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PackageRequestAcceptedEvent(
    UUID threadId, UUID packageRequestId,
    UUID senderId, UUID travelerId,
    BigDecimal agreedPriceEur,
    UUID travelerAnnouncementId,
    BigDecimal weightKg,
    String description,
    String contentCategory,
    String paymentIntentId,
    String recipientName,
    String recipientPhone,
    BigDecimal declaredValueEur,
    LocalDateTime disclaimerSignedAt,
    String disclaimerSignedIp
) {}
