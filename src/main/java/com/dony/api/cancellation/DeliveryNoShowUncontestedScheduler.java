package com.dony.api.cancellation;

import com.dony.api.common.AuditService;
import com.dony.api.disputes.events.DisputeOpenedEvent;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Signalement d'absence à la livraison (scope DELIVERY) expiré sans
 * contestation → ouvre un litige "non contesté". Jamais de capture ni de
 * remboursement automatique ici : l'admin tranche toujours via
 * {@code AdminDisputesController.resolveDispute}.
 *
 * <p>L'ouverture du litige passe par {@link DisputeOpenedEvent} plutôt que par un
 * appel direct à {@code DisputeService} (package {@code disputes}) : CLAUDE.md
 * impose les Spring Events pour toute communication cross-package, et le chemin
 * "contesté" analogue ({@link CancellationService#contestDeliveryNoShow}) publie
 * déjà ce même event, consommé par {@code DisputeOpenedEventListener} qui route
 * vers {@code disputeService.openDeliveryNoShowDispute} (idempotent par
 * (bidId, type)). Réutiliser cet event fait converger les deux chemins
 * (contesté / non contesté) sur le même point d'entrée d'ouverture de litige.
 *
 * <p>Idempotent : chaque entité traitée est marquée {@link CancellationStatus#CONFIRMED}
 * avant publication de l'event, afin de ne plus être resélectionnée par
 * {@link CancellationRepository#findExpiredPendingByScope} (qui ne retourne que les
 * entités {@code PENDING_CONFIRMATION}) — sans cela l'event serait republié à
 * chaque exécution horaire tant que l'admin n'a pas résolu le litige, ce qui
 * renverrait une notification et incrémenterait un compteur métrique à chaque
 * passage (voir {@code NotificationDispatcher}/{@code BusinessMetricsListener}
 * qui écoutent {@link DisputeOpenedEvent}).
 */
@Component
public class DeliveryNoShowUncontestedScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryNoShowUncontestedScheduler.class);

    private static final String REASON_RECIPIENT_NO_SHOW = "RECIPIENT_NO_SHOW";
    private static final String TYPE_TRAVELER_DELIVERY_NO_SHOW = "TRAVELER_DELIVERY_NO_SHOW";

    private final CancellationRepository cancellationRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public DeliveryNoShowUncontestedScheduler(CancellationRepository cancellationRepository,
                                              BidRepository bidRepository,
                                              AnnouncementRepository announcementRepository,
                                              ApplicationEventPublisher eventPublisher,
                                              AuditService auditService) {
        this.cancellationRepository = cancellationRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @Transactional
    public void run() {
        cancellationRepository.findExpiredPendingByScope(CancellationScope.DELIVERY, OffsetDateTime.now())
                .forEach(this::openUncontestedDispute);
    }

    private void openUncontestedDispute(CancellationEntity c) {
        BidEntity bid = bidRepository.findById(c.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("DeliveryNoShowUncontestedScheduler: bid {} introuvable pour cancellation {} — ignoré",
                    c.getBidId(), c.getId());
            return;
        }
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        if (announcement == null) {
            log.warn("DeliveryNoShowUncontestedScheduler: annonce {} introuvable pour bid {} (cancellation {}) — ignoré",
                    bid.getAnnouncementId(), c.getBidId(), c.getId());
            return;
        }

        String type = REASON_RECIPIENT_NO_SHOW.equals(c.getReason())
                ? REASON_RECIPIENT_NO_SHOW
                : TYPE_TRAVELER_DELIVERY_NO_SHOW;

        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepository.save(c);

        eventPublisher.publishEvent(new DisputeOpenedEvent(
                c.getBidId(), bid.getSenderId(), announcement.getTravelerId(), type));

        auditService.log("CANCELLATION", c.getId(), "DELIVERY_NOSHOW_UNCONTESTED_DISPUTE_OPENED", null,
                Map.of("bidId", c.getBidId().toString(), "type", type));
    }
}
