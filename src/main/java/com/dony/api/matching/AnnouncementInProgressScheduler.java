package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.AnnouncementInProgressEvent;
import com.dony.api.matching.events.BidExpiredOnDepartureEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Runs every hour. For each ACTIVE/FULL announcement whose departure time has passed:
 *
 * <ul>
 *   <li>If no ACCEPTED bids remain → directly COMPLETED (all bids were delivered before departure).</li>
 *   <li>Otherwise → IN_PROGRESS + auto-expires all remaining PENDING bids + publishes events.</li>
 * </ul>
 *
 * Idempotent: selects only ACTIVE/FULL statuses, so already-transitioned announcements are skipped.
 * Timezone: departure_date/time is interpreted in the announcement's stored timezone (default Europe/Paris).
 */
@Component
public class AnnouncementInProgressScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementInProgressScheduler.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Paris");

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AnnouncementInProgressScheduler(AnnouncementRepository announcementRepository,
                                           BidRepository bidRepository,
                                           AuditService auditService,
                                           ApplicationEventPublisher eventPublisher) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @Transactional
    public void processInProgressTransitions() {
        // Resolve "now" in Europe/Paris — covers all current departure cities.
        ZonedDateTime nowParis = ZonedDateTime.now(DEFAULT_ZONE);
        LocalDate today = nowParis.toLocalDate();
        LocalTime nowTime = nowParis.toLocalTime();

        List<AnnouncementEntity> candidates =
                announcementRepository.findDepartedActiveAnnouncements(today, nowTime);

        log.debug("AnnouncementInProgressScheduler: {} candidate(s) to process", candidates.size());

        for (AnnouncementEntity announcement : candidates) {
            try {
                processAnnouncement(announcement);
            } catch (Exception e) {
                log.error("Failed to process announcement {}: {}", announcement.getId(), e.getMessage(), e);
            }
        }
    }

    private void processAnnouncement(AnnouncementEntity announcement) {
        AnnouncementStatus previous = announcement.getStatus();
        boolean hasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatus(
                announcement.getId(), BidStatus.ACCEPTED);

        if (!hasAcceptedBids) {
            // All bids were delivered (or none existed) — go straight to COMPLETED.
            announcement.setStatus(AnnouncementStatus.COMPLETED);
            announcementRepository.save(announcement);
            auditService.log("ANNOUNCEMENT", announcement.getTravelerId(),
                    "ANNOUNCEMENT_COMPLETED", announcement.getId(),
                    Map.of("previousStatus", previous.name(), "trigger", "DEPARTURE_NO_ACCEPTED_BIDS"));
            log.info("Announcement {} → COMPLETED (no ACCEPTED bids at departure)", announcement.getId());
        } else {
            // Active deliveries remain — mark as in-progress.
            announcement.setStatus(AnnouncementStatus.IN_PROGRESS);
            announcementRepository.save(announcement);
            auditService.log("ANNOUNCEMENT", announcement.getTravelerId(),
                    "ANNOUNCEMENT_IN_PROGRESS", announcement.getId(),
                    Map.of("previousStatus", previous.name()));
            eventPublisher.publishEvent(
                    new AnnouncementInProgressEvent(announcement.getId(), announcement.getTravelerId()));
            log.info("Announcement {} → IN_PROGRESS", announcement.getId());
        }

        // Expire all PENDING bids — the traveler has departed and can no longer accept new parcels.
        expirePendingBids(announcement);
    }

    private void expirePendingBids(AnnouncementEntity announcement) {
        List<BidEntity> pendingBids = bidRepository.findByAnnouncementIdAndStatus(
                announcement.getId(), BidStatus.PENDING);

        for (BidEntity bid : pendingBids) {
            bid.setStatus(BidStatus.EXPIRED);
            bidRepository.save(bid);
            auditService.log("BID", bid.getId(), "BID_EXPIRED_ON_DEPARTURE",
                    announcement.getTravelerId(),
                    Map.of("announcementId", announcement.getId().toString()));
            eventPublisher.publishEvent(new BidExpiredOnDepartureEvent(
                    bid.getId(), bid.getSenderId(), announcement.getId(), announcement.getTravelerId()));
            log.info("Bid {} → EXPIRED (departure reached)", bid.getId());
        }
    }
}
