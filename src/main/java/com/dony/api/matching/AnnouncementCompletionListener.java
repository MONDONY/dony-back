package com.dony.api.matching;

import com.dony.api.common.AuditService;
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

/**
 * Transitions an {@link AnnouncementEntity} to {@link AnnouncementStatus#COMPLETED} the moment
 * its last in-flight bid is delivered. Côté UX traveler "Mes trajets", l'annonce bascule alors
 * de l'onglet "À venir" vers "Historique" sans attendre la date de départ.
 *
 * <p>Règle : un trajet est terminé dès qu'il n'existe plus aucun bid en statut
 * {@link BidStatus#ACCEPTED} sur l'annonce. PENDING et AWAITING_PAYMENT ne sont pas pris
 * en compte (ce sont des engagements pré-acceptation, pas des colis en cours de livraison).
 *
 * <p>Listens AFTER_COMMIT pour qu'un rollback de la transaction tracking ne produise pas une
 * transition fantôme. Idempotent : la même annonce déjà en COMPLETED ne sera pas re-loguée.
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
            log.warn("DeliveryConfirmedEvent received for unknown bidId={} — skipping announcement completion check",
                    event.getBidId());
            return;
        }

        BidEntity bid = bidOpt.get();
        Optional<AnnouncementEntity> announcementOpt = announcementRepository.findById(bid.getAnnouncementId());
        if (announcementOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent for bidId={} references unknown announcementId={} — skipping",
                    bid.getId(), bid.getAnnouncementId());
            return;
        }

        AnnouncementEntity announcement = announcementOpt.get();

        if (announcement.getStatus() == AnnouncementStatus.COMPLETED
                || announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            // Idempotence : déjà finalisé ou annulé, on ne touche pas.
            return;
        }

        boolean stillHasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatus(
                announcement.getId(), BidStatus.ACCEPTED);
        if (stillHasAcceptedBids) {
            // Au moins un colis encore à livrer : on garde le trajet en cours.
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
                        "lastDeliveredBidId", bid.getId().toString()
                )
        );

        log.info("Announcement {} transitioned to COMPLETED (last delivered bid={}, previousStatus={})",
                announcement.getId(), bid.getId(), previousStatus);
    }
}
