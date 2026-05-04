package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.ParcelRefusedEvent;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transitions an {@link AnnouncementEntity} to {@link AnnouncementStatus#COMPLETED} when all
 * in-flight bids are resolved. Triggers on three events:
 * <ul>
 *   <li>{@link DeliveryConfirmedEvent} — last ACCEPTED bid delivered via QR scan</li>
 *   <li>{@link VoyageurNoShowEvent} — traveler no-showed, bid → NO_SHOW</li>
 *   <li>{@link ParcelRefusedEvent} — traveler refused parcel, bid → PARCEL_REFUSED</li>
 * </ul>
 *
 * <p>Rule: a trip is done when no bid on the announcement remains in ACCEPTED status.
 * PENDING/EXPIRED are not counted (pre-departure commitments, not in-flight).
 *
 * <p>Idempotent: COMPLETED/CANCELLED announcements are silently skipped.
 * Uses AFTER_COMMIT to prevent phantom transitions on rollback.
 */
@Component
public class AnnouncementCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementCompletionListener.class);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;

    public AnnouncementCompletionListener(BidRepository bidRepository,
                                          AnnouncementRepository announcementRepository,
                                          AuditService auditService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        Optional<BidEntity> bidOpt = bidRepository.findById(event.getBidId());
        if (bidOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent for unknown bidId={} — skipping", event.getBidId());
            return;
        }
        checkAndCompleteIfDone(bidOpt.get().getAnnouncementId(), event.getBidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVoyageurNoShow(VoyageurNoShowEvent event) {
        Optional<BidEntity> bidOpt = bidRepository.findById(event.getBidId());
        if (bidOpt.isEmpty()) {
            log.warn("VoyageurNoShowEvent for unknown bidId={} — skipping", event.getBidId());
            return;
        }
        checkAndCompleteIfDone(bidOpt.get().getAnnouncementId(), event.getBidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onParcelRefused(ParcelRefusedEvent event) {
        Optional<BidEntity> bidOpt = bidRepository.findById(event.getBidId());
        if (bidOpt.isEmpty()) {
            log.warn("ParcelRefusedEvent for unknown bidId={} — skipping", event.getBidId());
            return;
        }
        checkAndCompleteIfDone(bidOpt.get().getAnnouncementId(), event.getBidId());
    }

    private void checkAndCompleteIfDone(UUID announcementId, UUID triggeringBidId) {
        Optional<AnnouncementEntity> announcementOpt = announcementRepository.findById(announcementId);
        if (announcementOpt.isEmpty()) {
            log.warn("Completion check for unknown announcementId={} (triggering bid={}) — skipping",
                    announcementId, triggeringBidId);
            return;
        }

        AnnouncementEntity announcement = announcementOpt.get();

        if (announcement.getStatus() == AnnouncementStatus.COMPLETED
                || announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            return;
        }

        boolean stillHasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatus(
                announcementId, BidStatus.ACCEPTED);
        if (stillHasAcceptedBids) {
            return;
        }

        AnnouncementStatus previousStatus = announcement.getStatus();
        announcement.setStatus(AnnouncementStatus.COMPLETED);
        announcementRepository.save(announcement);

        auditService.log(
                "ANNOUNCEMENT",
                announcement.getTravelerId(),
                "ANNOUNCEMENT_COMPLETED",
                announcement.getId(),
                Map.of(
                        "previousStatus", previousStatus.name(),
                        "lastDeliveredBidId", triggeringBidId.toString()
                )
        );

        log.info("Announcement {} → COMPLETED (triggering bid={}, previousStatus={})",
                announcement.getId(), triggeringBidId, previousStatus);
    }
}
