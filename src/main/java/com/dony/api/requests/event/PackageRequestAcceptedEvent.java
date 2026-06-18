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
    String disclaimerSignedIp,
    com.dony.api.payments.cash.PaymentMethod paymentMethod,
    /** Clés S3 des photos colis de la demande (package_requests/…) à copier vers le bid. */
    java.util.List<String> photoObjectKeys
) {}
