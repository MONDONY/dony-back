package com.dony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminBidListItemResponse(
    UUID id,
    String status,
    UUID announcementId,
    String senderName,
    String travelerName,
    String corridor,
    BigDecimal weightKg,
    BigDecimal netEur,
    String paymentMethod,
    LocalDateTime createdAt
) {}
