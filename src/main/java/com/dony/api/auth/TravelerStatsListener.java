package com.dony.api.auth;

import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Optional;

/**
 * Increments {@code users.total_trips} for the traveler the FIRST time a bid of one of
 * his announcements reaches COMPLETED. Subsequent bids COMPLETED on the same announcement
 * do NOT trigger another increment — one physical trip = one increment, regardless of
 * how many parcels are delivered on that trip.
 *
 * <p>Listens AFTER_COMMIT so a rollback of the originating tracking transaction does not
 * produce a phantom increment. Idempotence is enforced via
 * {@code announcements.total_trips_counted}.
 */
@Component
public class TravelerStatsListener {

    private static final Logger log = LoggerFactory.getLogger(TravelerStatsListener.class);

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;
    private final CacheManager cacheManager;

    public TravelerStatsListener(UserRepository userRepository,
                                 BidRepository bidRepository,
                                 AnnouncementRepository announcementRepository,
                                 AuditService auditService,
                                 CacheManager cacheManager) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        Optional<BidEntity> bidOpt = bidRepository.findById(event.getBidId());
        if (bidOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent received for unknown bidId={} — skipping total_trips increment",
                    event.getBidId());
            return;
        }

        BidEntity bid = bidOpt.get();

        // Defensive check: the event should only be published after the bid moves to COMPLETED,
        // but guard against edge cases (manual republish, future refactors).
        if (bid.getStatus() != BidStatus.COMPLETED) {
            log.warn("DeliveryConfirmedEvent received for bidId={} with status={} (expected COMPLETED) — skipping",
                    bid.getId(), bid.getStatus());
            return;
        }

        Optional<AnnouncementEntity> announcementOpt = announcementRepository.findById(bid.getAnnouncementId());
        if (announcementOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent for bidId={} references unknown announcementId={} — skipping",
                    bid.getId(), bid.getAnnouncementId());
            return;
        }

        AnnouncementEntity announcement = announcementOpt.get();

        if (announcement.isTotalTripsCounted()) {
            log.debug("total_trips already counted for announcementId={} — skipping (idempotent, bidId={})",
                    announcement.getId(), bid.getId());
            return;
        }

        Optional<UserEntity> travelerOpt = userRepository.findById(event.getTravelerId());
        if (travelerOpt.isEmpty()) {
            // Anomaly: a deleted/missing traveler completed a delivery. Mark the announcement
            // as counted to prevent infinite retry loops, log at ERROR for monitoring (Sentry)
            // and manual investigation.
            log.error("DeliveryConfirmedEvent: traveler {} not found for bidId={} announcementId={} — marking counted to stop retries; manual investigation required",
                    event.getTravelerId(), bid.getId(), announcement.getId());
            announcement.setTotalTripsCounted(true);
            announcementRepository.save(announcement);
            return;
        }

        UserEntity traveler = travelerOpt.get();
        int newTotal = traveler.getTotalTrips() + 1;
        traveler.setTotalTrips(newTotal);
        userRepository.save(traveler);

        announcement.setTotalTripsCounted(true);
        announcementRepository.save(announcement);

        var cache = cacheManager.getCache("trips-summary");
        if (cache != null) {
            cache.evict(traveler.getId());
        }

        auditService.log(
                "USER",
                traveler.getId(),
                "TOTAL_TRIPS_INCREMENTED",
                traveler.getId(),
                Map.of(
                        "bidId", bid.getId().toString(),
                        "announcementId", announcement.getId().toString(),
                        "newTotal", String.valueOf(newTotal)
                )
        );

        log.info("total_trips incremented for traveler={} (announcement={}, bid={}, newTotal={})",
                traveler.getId(), announcement.getId(), bid.getId(), newTotal);
    }
}
