package com.dony.api.ratings;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.ratings.dto.RatingRequest;
import com.dony.api.ratings.dto.RatingResponse;
import com.dony.api.ratings.dto.RecipientRatingRequest;
import com.dony.api.ratings.events.RatingCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RatingService {

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);
    private static final int RATING_WINDOW_DAYS = 7;

    private final RatingRepository ratingRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public RatingService(RatingRepository ratingRepository,
                         BidRepository bidRepository,
                         AnnouncementRepository announcementRepository,
                         UserRepository userRepository,
                         AuditService auditService,
                         ApplicationEventPublisher eventPublisher) {
        this.ratingRepository = ratingRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    // Story 9.1 — Notation par l'expéditeur authentifié
    @Transactional
    public RatingResponse createRating(String firebaseUid, RatingRequest request) {
        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));

        BidEntity bid = bidRepository.findById(request.bidId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Envoi introuvable"));

        if (!bid.getSenderId().equals(sender.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de cet envoi");
        }

        if (bid.getStatus() != BidStatus.COMPLETED) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-delivered",
                    "Unprocessable", "La livraison n'a pas encore été confirmée pour cet envoi");
        }

        if (bid.getUpdatedAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(RATING_WINDOW_DAYS))) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "rating-window-expired",
                    "Unprocessable", "La fenêtre de notation est expirée");
        }

        if (ratingRepository.existsByBidIdAndRaterId(bid.getId(), sender.getId())) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "already-rated", "Conflict",
                    "Vous avez déjà noté ce voyageur pour cet envoi");
        }

        UUID travelerId = resolveTravelerId(bid);

        RatingEntity rating = new RatingEntity();
        rating.setRaterId(sender.getId());
        rating.setRatedUserId(travelerId);
        rating.setBidId(bid.getId());
        rating.setStars(request.stars());
        rating.setComment(request.comment());
        ratingRepository.save(rating);

        recalculateAverageRating(travelerId);

        auditService.log("RATING", rating.getId(), "RATING_CREATED", sender.getId(),
                Map.of("bidId", bid.getId().toString(), "stars", request.stars()));

        eventPublisher.publishEvent(new RatingCreatedEvent(rating.getId(), travelerId, sender.getId(), request.stars()));

        log.info("Rating created: bid={} rater={} stars={}", bid.getId(), sender.getId(), request.stars());
        return toResponse(rating);
    }

    // Story 9.2 — Notation par le destinataire sans compte
    @Transactional
    public RatingResponse createRecipientRating(RecipientRatingRequest request) {
        BidEntity bid = bidRepository.findByTrackingToken(request.trackingToken())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Lien de suivi invalide"));

        if (bid.getStatus() != BidStatus.COMPLETED) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-delivered",
                    "Unprocessable", "La livraison n'a pas encore été confirmée");
        }

        if (ratingRepository.existsByBidIdAndTrackingToken(bid.getId(), request.trackingToken())) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "already-rated", "Conflict",
                    "Vous avez déjà noté ce voyageur");
        }

        UUID travelerId = resolveTravelerId(bid);

        RatingEntity rating = new RatingEntity();
        rating.setRatedUserId(travelerId);
        rating.setBidId(bid.getId());
        rating.setTrackingToken(request.trackingToken());
        rating.setStars(request.stars());
        rating.setComment(request.comment());
        ratingRepository.save(rating);

        recalculateAverageRating(travelerId);

        auditService.log("RATING", rating.getId(), "RECIPIENT_RATING_CREATED", null,
                Map.of("bidId", bid.getId().toString(), "stars", request.stars()));

        eventPublisher.publishEvent(new RatingCreatedEvent(rating.getId(), travelerId, null, request.stars()));

        log.info("Recipient rating created: bid={} stars={}", bid.getId(), request.stars());
        return toResponse(rating);
    }

    @Transactional
    public void recalculateAverageRating(UUID userId) {
        List<RatingEntity> ratings = ratingRepository.findIncludedRatingsByRatedUserId(userId);
        if (ratings.isEmpty()) return;

        double avg = ratings.stream().mapToInt(RatingEntity::getStars).average().orElse(0.0);
        BigDecimal rounded = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);

        userRepository.findById(userId).ifPresent(user -> {
            user.setAverageRating(rounded);
            userRepository.save(user);
        });
    }

    // Traveler is the owner of the announcement this bid is placed on
    private UUID resolveTravelerId(BidEntity bid) {
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "data-inconsistency", "Error",
                        "Annonce introuvable pour cet envoi"));
        return announcement.getTravelerId();
    }

    private RatingResponse toResponse(RatingEntity r) {
        return new RatingResponse(r.getId(), r.getRatedUserId(), r.getBidId(),
                r.getStars(), r.getComment(), r.getCreatedAt());
    }
}
