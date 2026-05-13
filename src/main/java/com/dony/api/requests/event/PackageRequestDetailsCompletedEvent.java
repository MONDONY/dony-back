package com.dony.api.requests.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired by PackageRequestService.completeDetails after the sender fills in
 * the post-acceptance details (recipient + declared value + disclaimer).
 * The matching/ package listens to propagate these onto the marketplace-issued bid.
 */
public record PackageRequestDetailsCompletedEvent(
    UUID packageRequestId,
    UUID threadId,
    UUID senderId,
    String recipientName,
    String recipientPhone,
    BigDecimal declaredValueEur,
    LocalDateTime disclaimerSignedAt,
    String disclaimerSignedIp
) {}
