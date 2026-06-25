package com.dony.api.admin.dto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminDisputeDetailResponse(
    UUID id, UUID bidId, String type, String status,
    String senderName, String travelerName,
    boolean refundFrozen, LocalDateTime createdAt,
    String resolution, OffsetDateTime resolvedAt, String resolutionNote,
    BigDecimal declaredValueEur, UUID beneficiaryUserId
) {}
