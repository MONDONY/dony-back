package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Toutes les 5 minutes, annule automatiquement les bids PENDING dont le voyageur
 * n'a pas répondu dans le délai = min(createdAt + 24h, departureDate - 12h).
 *
 * Réutilise BidRejectedEventListener pour libérer le hold Stripe (0 frais)
 * via la publication d'un BidRejectedEvent avec raison "TRAVELER_NO_RESPONSE".
 */
@Component
public class BidTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(BidTimeoutScheduler.class);

    private final BidRepository bidRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public BidTimeoutScheduler(BidRepository bidRepository,
                               AuditService auditService,
                               ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void autoCancelUnansweredBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime twentyFourHoursAgo = now.minusHours(24);
        // Approximation : si departure_date <= demain, on est au plus tard à H-12
        // (puisque H-12 = midi de la veille, donc dès qu'on est dans la journée
        // précédant la date de départ, le seuil est franchi). Acceptable pour v1.
        LocalDate tomorrow = now.toLocalDate().plusDays(1);

        List<BidEntity> timedOut = bidRepository.findPendingTimedOut(twentyFourHoursAgo, tomorrow);

        for (BidEntity bid : timedOut) {
            bid.setStatus(BidStatus.CANCELLED);
            bid.setRejectionReason("TRAVELER_NO_RESPONSE");
            bidRepository.save(bid);

            auditService.log("BID", bid.getId(), "BID_AUTO_CANCELLED_TIMEOUT", null,
                    Map.of(
                            "paymentIntentId", String.valueOf(bid.getPaymentIntentId()),
                            "reason", "TRAVELER_NO_RESPONSE"
                    ));

            // Le BidRejectedEventListener (existant) va annuler le hold Stripe ou rembourser
            eventPublisher.publishEvent(new BidRejectedEvent(
                    bid.getId(), bid.getSenderId(), "TRAVELER_NO_RESPONSE"));

            log.info("Bid {} auto-cancelled (no traveler response within timeout)", bid.getId());
        }
    }
}
