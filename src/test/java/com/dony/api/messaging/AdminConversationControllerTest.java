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
    @Mock com.dony.api.auth.UserRepository userRepository;

    AdminConversationController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminConversationController(repo, firestoreService, auditService, userRepository);
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

    @Test
    void getMessages_mapsFirestoreDocs_andResolvesSenders() {
        UUID senderId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), senderId, UUID.randomUUID(), "conv_1");
        when(repo.findByFirestoreConversationId("conv_1")).thenReturn(Optional.of(conv));

        java.util.Map<String, Object> msg1 = new java.util.HashMap<>();
        msg1.put("id", "m1");
        msg1.put("senderId", senderId.toString());
        msg1.put("body", "Bonjour");
        msg1.put("sentAt", "2026-07-01T10:00:00Z");
        java.util.Map<String, Object> msg2 = new java.util.HashMap<>();
        msg2.put("id", "m2");
        msg2.put("senderId", "SYSTEM");
        msg2.put("body", "Colis remis");
        msg2.put("sentAt", "2026-07-01T11:00:00Z");
        msg2.put("deletedAt", "2026-07-02T09:00:00Z");
        when(firestoreService.listMessages("conv_1")).thenReturn(java.util.List.of(msg1, msg2));

        UserEntity sender = new UserEntity();
        sender.setFirstName("Awa");
        sender.setLastName("Diop");
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of(sender));

        var resp = controller.getMessages("conv_1");
        var messages = resp.getBody();

        org.assertj.core.api.Assertions.assertThat(messages).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(messages.get(0).content()).isEqualTo("Bonjour");
        org.assertj.core.api.Assertions.assertThat(messages.get(0).deleted()).isFalse();
        org.assertj.core.api.Assertions.assertThat(messages.get(1).senderName()).isEqualTo("Systeme");
        org.assertj.core.api.Assertions.assertThat(messages.get(1).deleted()).isTrue();
    }

    @Test
    void getMessages_unknownConversation_throws404() {
        when(repo.findByFirestoreConversationId("ghost")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.getMessages("ghost"));
    }

    @Test
    void listConversations_flaggedTrue_returnsEmptyPage() {
        var resp = controller.listAllConversations(true, 0, 20);
        org.assertj.core.api.Assertions.assertThat(resp.getBody().getTotalElements()).isZero();
        verifyNoInteractions(firestoreService);
    }
}
