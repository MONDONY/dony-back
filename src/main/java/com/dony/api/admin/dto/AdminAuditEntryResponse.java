package com.dony.api.admin.dto;

import com.dony.api.common.AuditLogEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record AdminAuditEntryResponse(
    Long id,
    String action,
    String entityType,
    UUID entityId,
    UUID actorId,
    String actorName,
    String ipAddress,
    Map<String, Object> payload,
    LocalDateTime createdAt
) {
    public static AdminAuditEntryResponse from(AuditLogEntity e, String actorName) {
        return new AdminAuditEntryResponse(
            e.getId(), e.getAction(), e.getEntityType(), e.getEntityId(),
            e.getActorId(), actorName, e.getIpAddress(), e.getPayload(), e.getCreatedAt()
        );
    }
}
