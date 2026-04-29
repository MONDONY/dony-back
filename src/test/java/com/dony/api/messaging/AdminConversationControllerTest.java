package com.dony.api.messaging;

import com.dony.api.admin.AdminConversationController;
import com.dony.api.auth.UserEntity;
import com.dony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminConversationControllerTest {

    @Mock ConversationRepository repo;
    @Mock FirestoreService firestoreService;
    @Mock AuditService auditService;

    AdminConversationController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminConversationController(repo, firestoreService, auditService);
    }

    @Test
    void deleteMessage_callsSoftDeleteAndAudit() {
        UUID bidId = UUID.randomUUID();
        var conv = new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv_test");
        when(repo.findByFirestoreConversationId("conv_test")).thenReturn(Optional.of(conv));

        UserEntity admin = mock(UserEntity.class);
        when(admin.getId()).thenReturn(UUID.randomUUID());

        controller.deleteMessage("conv_test", "msg_001", admin);

        verify(firestoreService).softDeleteMessage("conv_test", "msg_001");
        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_DELETED"), any(), anyMap());
    }

    @Test
    void deleteMessage_returns404_whenConversationNotFound() {
        when(repo.findByFirestoreConversationId("conv_unknown")).thenReturn(Optional.empty());
        UserEntity admin = mock(UserEntity.class);

        try {
            controller.deleteMessage("conv_unknown", "msg_001", admin);
            throw new AssertionError("Expected exception");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assert e.getStatusCode().value() == 404;
        }
    }
}
