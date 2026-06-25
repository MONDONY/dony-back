package com.dony.api.admin.dto;

import com.dony.api.auth.UserEntity;
import com.dony.api.ratings.RatingEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record AdminRatingResponse(
        UUID id,
        UUID bidId,
        int score,
        String comment,
        String fromUserName,
        String toUserName,
        boolean flagged,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AdminRatingResponse from(RatingEntity e, Map<UUID, UserEntity> users) {
        return new AdminRatingResponse(
                e.getId(),
                e.getBidId(),
                e.getStars(),
                e.getComment(),
                userName(e.getRaterId(), users),
                userName(e.getRatedUserId(), users),
                e.isFlagged(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private static String userName(UUID userId, Map<UUID, UserEntity> users) {
        if (userId == null) return null;
        UserEntity u = users.get(userId);
        if (u == null) return null;
        return u.getFirstName() + (u.getLastName() != null ? " " + u.getLastName() : "");
    }
}
