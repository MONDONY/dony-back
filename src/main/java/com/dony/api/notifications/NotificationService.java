package com.dony.api.notifications;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.PageResponse;
import com.dony.api.notifications.dto.NotificationDTO;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private final NotificationRepository repository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    public NotificationEntity persist(UUID userId, String type, String title, String body,
                                      Map<String, String> data, boolean isCritical) {
        return repository.save(new NotificationEntity(userId, type, title, body, data, isCritical));
    }

    public NotificationEntity persist(UUID userId, String type, String title, String body,
                                      Map<String, String> data) {
        return persist(userId, type, title, body, data, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDTO> list(String firebaseUid, int page, int size) {
        UUID userId = resolveUserId(firebaseUid);
        return PageResponse.from(
                repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                          .map(NotificationDTO::from));
    }

    @Transactional(readOnly = true)
    public long countUnread(String firebaseUid) {
        return repository.countByUserIdAndReadAtIsNull(resolveUserId(firebaseUid));
    }

    public void markRead(String firebaseUid, UUID notificationId) {
        var entity = requireOwned(firebaseUid, notificationId);
        if (!entity.isRead()) {
            entity.markRead(LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    public int markAllRead(String firebaseUid) {
        return repository.markAllReadByUserId(resolveUserId(firebaseUid), LocalDateTime.now(ZoneOffset.UTC));
    }

    public void softDelete(String firebaseUid, UUID notificationId) {
        requireOwned(firebaseUid, notificationId)
                .setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    // Story 8.3 — Flutter sends ACK to prevent SMS fallback
    public void ack(String firebaseUid, UUID notificationId) {
        var entity = requireOwned(firebaseUid, notificationId);
        if (entity.getAckedAt() == null) {
            entity.markAcked(LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    private NotificationEntity requireOwned(String firebaseUid, UUID notificationId) {
        UUID userId = resolveUserId(firebaseUid);
        var entity = repository.findById(notificationId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "not-found", "Not found", "Notification introuvable"));
        if (!entity.getUserId().equals(userId)) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Accès refusé");
        }
        return entity;
    }

    private UUID resolveUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));
    }
}