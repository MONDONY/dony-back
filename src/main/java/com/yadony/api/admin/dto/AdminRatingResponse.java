package com.yadony.api.admin.dto;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.ratings.RatingEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record AdminRatingResponse(
        UUID id,
        UUID bidId,
        String raterName,
        String ratedName,
        int score,
        String comment,
        boolean flagged,
        boolean excluded,
        String excludedReason,
        LocalDateTime createdAt
) {
    public static AdminRatingResponse from(RatingEntity e, Map<UUID, UserEntity> users) {
        return new AdminRatingResponse(
                e.getId(),
                e.getBidId(),
                userName(e.getRaterId(), users),
                userName(e.getRatedUserId(), users),
                e.getStars(),
                e.getComment(),
                e.isFlagged(),
                e.isExcludedFromAverage(),
                e.getExcludedReason(),
                e.getCreatedAt()
        );
    }

    private static String userName(UUID userId, Map<UUID, UserEntity> users) {
        if (userId == null) return null;
        UserEntity u = users.get(userId);
        if (u == null) return null;
        return MatchingTextUtil.buildName(u);
    }
}
