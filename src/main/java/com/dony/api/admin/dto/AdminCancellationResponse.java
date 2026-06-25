package com.dony.api.admin.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminCancellationResponse(
        UUID id,
        UUID bidId,
        UUID cancelledBy,
        String reason,
        String refundStatus,
        String rematchStatus,
        String noShowStatus,
        OffsetDateTime contestationDeadline,
        LocalDateTime createdAt
) {}
