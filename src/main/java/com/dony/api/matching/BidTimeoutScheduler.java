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

    /** Minimum bid age before auto-cancellation kicks in (regardless of departure date). */
    static final int MIN_GRACE_MINUTES = 120;

    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void autoCancelUnansweredBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime twentyFourHoursAgo = now.minusHours(24);
        LocalDateTime minGraceThreshold = now.minusMinutes(MIN_GRACE_MINUTES);
        // H-12 threshold: a PENDING bid is past H-12 once `now + 12h` has reached
        // the start of `departureDate`. Equivalent to `departureDate <= (now + 12h).toLocalDate()`.
        LocalDate halfDayThresholdDate = now.plusHours(12).toLocalDate();

        List<BidEntity> timedOut = bidRepository.findPendingTimedOut(
                twentyFourHoursAgo, halfDayThresholdDate, minGraceThreshold);

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
