package com.dony.api.requests.dto;

import com.dony.api.requests.entity.NegotiationThreadStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record NegotiationThreadResponse(
    UUID id, UUID packageRequestId, UUID travelerId,
    UUID travelerAnnouncementId, LocalDate travelerTravelDate, BigDecimal travelerAvailableKg,
    NegotiationThreadStatus status, BigDecimal currentPriceEur, int roundsCount,
    LocalDateTime lastActivityAt, LocalDateTime createdAt,
    List<NegotiationMessageResponse> messages,
    String paymentIntentClientSecret
) {}
