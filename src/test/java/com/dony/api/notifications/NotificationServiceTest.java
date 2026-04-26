package com.dony.api.notifications;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository repository;
    @Mock UserRepository userRepository;

    NotificationService service;

    private final String uid = "firebase-uid-123";
    private final UUID userId = UUID.randomUUID();
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, userRepository);
        user = new UserEntity();
        user.setFirebaseUid(uid);
    }

    // helper to set id via reflection since BaseEntity uses @GeneratedValue
    private void setId(UserEntity u, UUID id) {
        try {
            var field = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(u, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void persist_savesEntityWithCorrectFields() {
        setId(user, userId);
        Map<String, String> data = Map.of("type", "BID_CREATED", "bidId", UUID.randomUUID().toString());

        service.persist(userId, "BID_CREATED", "Nouvelle demande", "Test body", data);

        var captor = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getType()).isEqualTo("BID_CREATED");
        assertThat(saved.getTitle()).isEqualTo("Nouvelle demande");
        assertThat(saved.getBody()).isEqualTo("Test body");
        assertThat(saved.isRead()).isFalse();
    }

    @Test
    void list_returnsPageForAuthenticatedUser() {
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        var entity = new NotificationEntity(userId, "BID_ACCEPTED", "Accepté", "Corps", Map.of());
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(entity)));

        var result = service.list(uid, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).type()).isEqualTo("BID_ACCEPTED");
        assertThat(result.content().get(0).read()).isFalse();
    }

    @Test
    void list_throwsUnauthorizedWhenUserNotFound() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(uid, 0, 20))
                .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void markRead_updatesReadAtForOwner() {
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        var notifId = UUID.randomUUID();
        var entity = new NotificationEntity(userId, "BID_CREATED", "T", "B", Map.of());
        when(repository.findById(notifId)).thenReturn(Optional.of(entity));

        service.markRead(uid, notifId);

        assertThat(entity.isRead()).isTrue();
    }

    @Test
    void markRead_throwsForbiddenForWrongOwner() {
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        var notifId = UUID.randomUUID();
        var entity = new NotificationEntity(UUID.randomUUID(), "BID_CREATED", "T", "B", Map.of());
        when(repository.findById(notifId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.markRead(uid, notifId))
                .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void countUnread_returnsRepositoryCount() {
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        when(repository.countByUserIdAndReadAtIsNull(userId)).thenReturn(5L);

        assertThat(service.countUnread(uid)).isEqualTo(5L);
    }

    @Test
    void markAllRead_callsRepository() {
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        when(repository.markAllReadByUserId(eq(userId), any(LocalDateTime.class))).thenReturn(3);

        int count = service.markAllRead(uid);

        assertThat(count).isEqualTo(3);
        verify(repository).markAllReadByUserId(eq(userId), any());
    }

    // ── softDelete ──────────────────────────────────────────────────────────────

    @Test
    void softDelete_setsDeletedAtForOwner() {
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        var notifId = UUID.randomUUID();
        var entity = new NotificationEntity(userId, "BID_CREATED", "T", "B", Map.of());
        when(repository.findById(notifId)).thenReturn(Optional.of(entity));

        service.softDelete(uid, notifId);

        assertThat(entity.getDeletedAt()).isNotNull();
    }

    @Test
    void softDelete_throwsNotFoundWhenMissing() {
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        var notifId = UUID.randomUUID();
        when(repository.findById(notifId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.softDelete(uid, notifId))
                .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void softDelete_throwsForbiddenForWrongOwner() {
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        var notifId = UUID.randomUUID();
        var entity = new NotificationEntity(UUID.randomUUID(), "BID_CREATED", "T", "B", Map.of());
        when(repository.findById(notifId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.softDelete(uid, notifId))
                .isInstanceOf(DonyBusinessException.class);
    }
}
