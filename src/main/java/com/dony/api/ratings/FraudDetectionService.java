package com.dony.api.ratings;

import com.dony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);
    private static final int MAX_SAME_PAIR_30_DAYS = 3;

    private final RatingRepository ratingRepository;
    private final AuditService auditService;

    public FraudDetectionService(RatingRepository ratingRepository, AuditService auditService) {
        this.ratingRepository = ratingRepository;
        this.auditService = auditService;
    }

    // Story 9.7 — Détection de farming sur le système de notation
    @Transactional
    public void detectRatingFarming(UUID ratingId) {
        RatingEntity rating = ratingRepository.findById(ratingId).orElse(null);
        if (rating == null || rating.getRaterId() == null) return;

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
        List<RatingEntity> recentPairRatings = ratingRepository.findByRaterIdAndRatedUserIdSince(
                rating.getRaterId(), rating.getRatedUserId(), since);

        if (recentPairRatings.size() > MAX_SAME_PAIR_30_DAYS) {
            markSuspiciousRatings(recentPairRatings, rating.getRaterId(), rating.getRatedUserId());
            return;
        }

        // All ratings from same rater to same traveler are 5 stars with no comment
        boolean allPerfectNoComment = recentPairRatings.size() >= 2 &&
                recentPairRatings.stream().allMatch(r -> r.getStars() == 5 &&
                        (r.getComment() == null || r.getComment().isBlank()));

        if (allPerfectNoComment) {
            markSuspiciousRatings(recentPairRatings, rating.getRaterId(), rating.getRatedUserId());
        }
    }

    private void markSuspiciousRatings(List<RatingEntity> ratings, UUID raterId, UUID ratedUserId) {
        ratings.forEach(r -> r.setExcludedFromAverage(true));
        ratingRepository.saveAll(ratings);

        auditService.log("RATING", ratings.get(0).getId(), "FRAUD_ALERT_RATING_FARMING", null,
                Map.of("raterId", raterId.toString(),
                        "ratedUserId", ratedUserId.toString(),
                        "suspiciousCount", ratings.size()));

        log.warn("Rating farming detected: raterId={} ratedUserId={} count={}",
                raterId, ratedUserId, ratings.size());
    }
}
