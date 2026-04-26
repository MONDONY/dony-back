package com.dony.api.notifications.dto;

import com.dony.api.notifications.NotificationEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record NotificationDTO(
        UUID id,
        String type,
        String title,
        String body,
        Map<String, String> data,
        boolean read,
        LocalDateTime createdAt
) {
    public static NotificationDTO from(NotificationEntity e) {
        return new NotificationDTO(
                e.getId(),
                e.getType(),
                e.getTitle(),
                e.getBody(),
                e.getData(),
                e.isRead(),
                e.getCreatedAt()
        );
    }
}
