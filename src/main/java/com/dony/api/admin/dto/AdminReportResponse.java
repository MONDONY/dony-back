package com.dony.api.admin.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminReportResponse(
        UUID id,
        String targetType,
        UUID targetId,
        String reporterName,
        String reason,
        String description,
        String status,
        String actionTaken,
        String resolutionNote,
        OffsetDateTime resolvedAt,
        LocalDateTime createdAt
) {
}
