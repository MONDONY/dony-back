package com.dony.api.admin.dto;

import com.dony.api.admin.AdminAlertEntity;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminAlertResponse(
        UUID id,
        String type,
        String severity,
        String payload,
        boolean resolved,
        OffsetDateTime resolvedAt,
        LocalDateTime createdAt
) {
    public static AdminAlertResponse from(AdminAlertEntity e) {
        return new AdminAlertResponse(
                e.getId(),
                e.getType(),
                e.getSeverity(),
                e.getPayload(),
                e.isResolved(),
                e.getResolvedAt(),
                e.getCreatedAt()
        );
    }
}
