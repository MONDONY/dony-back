package com.dony.api.notifications;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.notifications.dto.NotificationDTO;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.PageResponse;
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

    public void persist(UUID userId, String type, String title, String body, Map<String, String> data) {
        var entity = new NotificationEntity(userId, type, title, body, data);
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDTO> list(String firebaseUid, int page, int size) {
        UUID userId = resolveUserId(firebaseUid);
        var pageResult = repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(NotificationDTO::from);
        return PageResponse.from(pageResult);
    }

    @Transactional(readOnly = true)
    public long countUnread(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    public void markRead(String firebaseUid, UUID notificationId) {
        UUID userId = resolveUserId(firebaseUid);
        var entity = repository.findById(notificationId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "not-found", "Not found", "Notification introuvable"));
        if (!entity.getUserId().equals(userId)) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Accès refusé");
        }
        if (!entity.isRead()) {
            entity.markRead(LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    public int markAllRead(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        return repository.markAllReadByUserId(userId, LocalDateTime.now(ZoneOffset.UTC));
    }

    private UUID resolveUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));
    }
}
