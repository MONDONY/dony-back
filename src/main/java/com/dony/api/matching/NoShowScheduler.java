package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

// Story 9.6 — Détection et sanction des no-shows voyageur
@Component
public class NoShowScheduler {

    private static final Logger log = LoggerFactory.getLogger(NoShowScheduler.class);
    private static final int NO_SHOW_GRACE_HOURS = 1;
    private static final int RECURRING_NO_SHOW_THRESHOLD = 2;

    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public NoShowScheduler(BidRepository bidRepository,
                           UserRepository userRepository,
                           AnnouncementRepository announcementRepository,
                           AuditService auditService,
                           ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 0 * * * *") // every hour
    @Transactional
    public void detectNoShows() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(NO_SHOW_GRACE_HOURS);
        List<BidEntity> noShowBids = bidRepository.findNoShowBids(cutoff);

        log.debug("No-show scheduler: {} bids to process", noShowBids.size());

        for (BidEntity bid : noShowBids) {
            try {
                processNoShow(bid);
            } catch (Exception e) {
                log.error("Error processing no-show for bid {}: {}", bid.getId(), e.getMessage(), e);
            }
        }
    }

    private void processNoShow(BidEntity bid) {
        bid.setStatus(BidStatus.NO_SHOW);
        bid.setNoShowAt(LocalDateTime.now(ZoneOffset.UTC));
        bidRepository.save(bid);

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        if (announcement == null) return;

        UserEntity traveler = userRepository.findById(announcement.getTravelerId()).orElse(null);
        if (traveler == null) return;

        int newCount = traveler.getNoShowCount() + 1;
        traveler.setNoShowCount(newCount);
        userRepository.save(traveler);

        auditService.log("BID", bid.getId(), "BID_NO_SHOW", traveler.getId(),
                Map.of("noShowCount", newCount, "senderId", bid.getSenderId().toString()));

        // VoyageurNoShowEvent triggers: notification to sender + escrow refund (via PaymentNoShowListener)
        eventPublisher.publishEvent(new VoyageurNoShowEvent(
                bid.getId(), traveler.getId(), bid.getSenderId(), newCount));

        log.info("No-show recorded for bid={} traveler={} noShowCount={}", bid.getId(), traveler.getId(), newCount);

        if (newCount >= RECURRING_NO_SHOW_THRESHOLD) {
            auditService.log("USER", traveler.getId(), "ADMIN_ALERT_RECURRING_NO_SHOW", traveler.getId(),
                    Map.of("noShowCount", newCount, "bidId", bid.getId().toString()));
            log.warn("Recurring no-show alert for traveler {}: count={}", traveler.getId(), newCount);
        }
    }
}
