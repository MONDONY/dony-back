package com.dony.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditService — tests unitaires")
class AuditServiceTest {

    @Mock private AuditLogRepository auditLogRepository;

    @InjectMocks private AuditService auditService;

    @Test
    @DisplayName("log() → enregistre correctement l'AuditLogEntity en base")
    void log_validData_savesEntity() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("key", "value", "count", 42);

        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.log("USER", entityId, "USER_CREATED", actorId, payload);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getEntityType()).isEqualTo("USER");
        assertThat(saved.getEntityId()).isEqualTo(entityId);
        assertThat(saved.getAction()).isEqualTo("USER_CREATED");
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getPayload()).containsEntry("key", "value");
        assertThat(saved.getPayload()).containsEntry("count", 42);
    }

    @Test
    @DisplayName("log() → fonctionne avec un payload vide")
    void log_emptyPayload_savesEntity() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.log("BID", entityId, "BID_ACCEPTED", actorId, Map.of());

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getEntityType()).isEqualTo("BID");
        assertThat(saved.getAction()).isEqualTo("BID_ACCEPTED");
        assertThat(saved.getPayload()).isEmpty();
    }

    @Test
    @DisplayName("log() → fonctionne avec entityId et actorId null")
    void log_nullIds_savesEntityWithNulls() {
        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.log("SYSTEM", null, "STARTUP", null, Map.of("version", "1.0"));

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getEntityId()).isNull();
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getEntityType()).isEqualTo("SYSTEM");
    }

    private <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
