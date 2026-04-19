package com.dony.api.common;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void log(String entityType, UUID entityId, String action, UUID actorId, Map<String, Object> payload) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setAction(action);
        entry.setActorId(actorId);
        entry.setPayload(payload);
        auditLogRepository.save(entry);
    }
}
