package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Logique métier partagée pour marquer un voyageur en no-show. Extraite du
 * {@code NoShowScheduler} (cron) afin d'être réutilisée par le signalement manuel
 * de l'expéditeur (via {@code TravelerNoShowReportListener}).
 */
@Service
public class NoShowService {

    private static final Logger log = LoggerFactory.getLogger(NoShowService.class);
    private static final int RECURRING_NO_SHOW_THRESHOLD = 2;

    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public NoShowService(BidRepository bidRepository,
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

    /**
     * Marque un bid NO_SHOW (voyageur absent), incrémente le compteur du voyageur et
     * publie {@link VoyageurNoShowEvent} (→ remboursement escrow). Idempotent : no-op si
     * le bid n'est plus ACCEPTED (déjà traité par le cron ou un signalement concurrent).
     *
     * @param bidId le bid concerné
     * @param source "scheduler" | "sender_report" — tracé dans l'audit.
     */
    @Transactional
    public void recordTravelerNoShow(UUID bidId, String source) {
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null || bid.getStatus() != BidStatus.ACCEPTED) {
            return; // idempotent
        }

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
                Map.of("noShowCount", newCount, "senderId", bid.getSenderId().toString(), "source", source));

        // VoyageurNoShowEvent triggers: notification to sender + escrow refund (via PaymentNoShowListener)
        eventPublisher.publishEvent(new VoyageurNoShowEvent(
                bid.getId(), traveler.getId(), bid.getSenderId(), newCount));

        log.info("No-show recorded for bid={} traveler={} noShowCount={} source={}",
                bid.getId(), traveler.getId(), newCount, source);

        if (newCount >= RECURRING_NO_SHOW_THRESHOLD) {
            auditService.log("USER", traveler.getId(), "ADMIN_ALERT_RECURRING_NO_SHOW", traveler.getId(),
                    Map.of("noShowCount", newCount, "bidId", bid.getId().toString()));
            log.warn("Recurring no-show alert for traveler {}: count={}", traveler.getId(), newCount);
        }
    }
}
