package com.dony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BidResponse(
        UUID id,
        UUID announcementId,
        UUID senderId,
        String senderName,
        BigDecimal weightKg,
        BigDecimal declaredValueEur,
        String description,
        String contentCategory,
        String recipientName,
        String recipientPhone,
        String status,
        String rejectionReason,
        String handoverLocation,
        LocalDateTime handoverWindowStart,
        LocalDateTime handoverWindowEnd,
        boolean voyageurConfirmed,
        LocalDateTime disclaimerSignedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
