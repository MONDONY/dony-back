package com.dony.api.ratings;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BadgeService {

    private static final Logger log = LoggerFactory.getLogger(BadgeService.class);
    private static final int MIN_DELIVERIES = 5;
    private static final double MIN_AVERAGE_RATING = 4.0;
    private static final int MIN_DAYS_BETWEEN_DELIVERIES = 7;

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final RatingRepository ratingRepository;
    private final AuditService auditService;

    public BadgeService(UserRepository userRepository,
                        BidRepository bidRepository,
                        RatingRepository ratingRepository,
                        AuditService auditService) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.ratingRepository = ratingRepository;
        this.auditService = auditService;
    }

    @Transactional
    public void checkAndGrantKiloPro(UUID travelerId) {
        UserEntity traveler = userRepository.findById(travelerId).orElse(null);
        if (traveler == null || traveler.isKiloPro() || traveler.getStatus().name().equals("SUSPENDED")) {
            return;
        }

        List<BidEntity> completedBids = bidRepository.findBySenderId(travelerId).stream()
                .filter(b -> b.getStatus() == BidStatus.COMPLETED)
                .collect(Collectors.toList());

        // Actually the traveler's bids are found via announcement, not senderId
        // We need the bids where the traveler is the announcement owner
        // This is handled by AnnouncementRepository; here we use a simpler approach:
        // Query completed bids via announcement's travelerId via a JPQL query we'll add
        List<BidEntity> travelerDeliveries = bidRepository.findCompletedBidsByTravelerId(travelerId);

        if (travelerDeliveries.size() < MIN_DELIVERIES) {
            return;
        }

        // Anti-farming check 1: distinct recipient phone numbers
        long distinctRecipients = travelerDeliveries.stream()
                .map(BidEntity::getRecipientPhone)
                .filter(phone -> phone != null && !phone.isBlank())
                .distinct()
                .count();

        if (distinctRecipients < MIN_DELIVERIES) {
            auditService.log("USER", travelerId, "KILO_PRO_FARMING_DETECTED", travelerId,
                    Map.of("reason", "insufficient_distinct_recipients",
                            "distinctRecipients", distinctRecipients,
                            "totalDeliveries", travelerDeliveries.size()));
            log.warn("Kilo Pro farming detected for traveler {}: only {} distinct recipients",
                    travelerId, distinctRecipients);
            return;
        }

        // Anti-farming check 2: min 7 days between deliveries (check consecutive ones)
        List<LocalDateTime> sortedDates = travelerDeliveries.stream()
                .map(BidEntity::getUpdatedAt)
                .sorted()
                .collect(Collectors.toList());

        boolean hasMinInterval = true;
        for (int i = 1; i < sortedDates.size(); i++) {
            long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(
                    sortedDates.get(i - 1), sortedDates.get(i));
            if (daysBetween < MIN_DAYS_BETWEEN_DELIVERIES) {
                hasMinInterval = false;
                break;
            }
        }

        if (!hasMinInterval) {
            auditService.log("USER", travelerId, "KILO_PRO_FARMING_DETECTED", travelerId,
                    Map.of("reason", "deliveries_too_close_in_time"));
            log.warn("Kilo Pro farming detected for traveler {}: deliveries too close in time", travelerId);
            return;
        }

        // Check average rating on last 5 deliveries
        List<RatingEntity> recentRatings = ratingRepository.findRecentIncludedRatings(travelerId);
        if (recentRatings.size() < MIN_DELIVERIES) {
            return;
        }

        double avgLast5 = recentRatings.stream()
                .limit(MIN_DELIVERIES)
                .mapToInt(RatingEntity::getStars)
                .average()
                .orElse(0.0);

        if (avgLast5 < MIN_AVERAGE_RATING) {
            return;
        }

        traveler.setKiloPro(true);
        traveler.setKiloProGrantedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRepository.save(traveler);

        auditService.log("USER", travelerId, "KILO_PRO_GRANTED", travelerId,
                Map.of("avgLast5", avgLast5, "totalDeliveries", travelerDeliveries.size()));

        log.info("Kilo Pro granted to traveler {}", travelerId);
    }
}
