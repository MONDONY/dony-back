package com.dony.api.cancellation;

import com.dony.api.cancellation.events.BidLostRematchPreparedEvent;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.events.BidRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rematch bid-only : quand le voyageur annule le transport d'un colis payé
 * ({@code cancelBid}) ou refuse une demande déjà payée ({@code rejectBid}), sans annuler le
 * trajet lui-même, ce listener crée une {@code CancellationEntity} dédiée (scope
 * {@code HANDOVER} par défaut) et réutilise {@link RematchService#generateForCancellations}
 * TEL QUEL pour générer les suggestions de rematch — même logique que
 * {@code CancellationService.cancelTrip}, mais pour un seul bid.
 *
 * <p>Synchrone ({@code @EventListener}, pas {@code @TransactionalEventListener}) : la
 * cancellation et les suggestions committent dans la MÊME transaction que
 * {@code cancelBid}/{@code rejectBid} — même garantie que la génération inline dans
 * {@code cancelTrip}.
 *
 * <p>Ne réagit qu'aux events {@link BidRejectedEvent#isRematchEligible()}. Si l'annonce ou
 * le bid sont introuvables, log un warning et ne fait rien : aucune exception n'est levée
 * ici, car le remboursement AFTER_COMMIT côté paiements dépend du commit de la transaction
 * appelante — la faire échouer casserait le remboursement.
 */
@Component
public class BidLostRematchListener {

    private static final Logger log = LoggerFactory.getLogger(BidLostRematchListener.class);

    private static final String REASON_CANCELLED_BY_TRAVELER = "CANCELLED_BY_TRAVELER";

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final CancellationRepository cancellationRepository;
    private final RematchService rematchService;
    private final ApplicationEventPublisher eventPublisher;

    public BidLostRematchListener(BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   CancellationRepository cancellationRepository,
                                   RematchService rematchService,
                                   ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.cancellationRepository = cancellationRepository;
        this.rematchService = rematchService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onBidRejected(BidRejectedEvent event) {
        if (!event.isRematchEligible()) {
            return;
        }

        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("BidLostRematchListener: bid {} introuvable, rematch ignoré", event.getBidId());
            return;
        }

        UUID announcementId = event.getAnnouncementId() != null
                ? event.getAnnouncementId()
                : bid.getAnnouncementId();
        AnnouncementEntity announcement = announcementId != null
                ? announcementRepository.findById(announcementId).orElse(null)
                : null;
        if (announcement == null) {
            log.warn("BidLostRematchListener: annonce {} introuvable pour bid {}, rematch ignoré",
                    announcementId, event.getBidId());
            return;
        }

        boolean cancelledByTraveler = REASON_CANCELLED_BY_TRAVELER.equals(event.getReason());

        CancellationEntity cancellation = new CancellationEntity();
        cancellation.setBidId(bid.getId());
        cancellation.setCancelledBy(announcement.getTravelerId());
        cancellation.setReason(cancelledByTraveler
                ? CancellationReason.BID_CANCELLED_BY_TRAVELER.name()
                : CancellationReason.BID_REJECTED_AFTER_PAYMENT.name());
        cancellation = cancellationRepository.save(cancellation);

        Map<UUID, RematchService.RematchInfo> bySender = rematchService.generateForCancellations(
                announcement, List.of(bid), List.of(cancellation));
        RematchService.RematchInfo info = bySender.get(bid.getSenderId());
        int suggestionCount = info != null ? info.suggestionCount() : 0;

        eventPublisher.publishEvent(new BidLostRematchPreparedEvent(
                bid.getSenderId(), bid.getId(), cancellation.getId(), suggestionCount, cancelledByTraveler));
    }
}
