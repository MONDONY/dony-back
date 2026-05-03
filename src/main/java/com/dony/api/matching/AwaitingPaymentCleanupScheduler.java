package com.dony.api.matching;

import com.dony.api.payments.PaymentService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Toutes les 5 minutes, supprime physiquement les bids AWAITING_PAYMENT dont la fenêtre
 * de paiement (15 min après création) est dépassée. Annule le PaymentIntent associé pour
 * libérer le hold sur la carte de l'expéditeur (0 frais Stripe).
 *
 * Race condition gérée : si le webhook Stripe arrive au même moment et que la capture
 * a déjà eu lieu (Stripe répond payment_intent_unexpected_state), on promeut le bid en
 * PENDING au lieu de le supprimer.
 */
@Component
public class AwaitingPaymentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AwaitingPaymentCleanupScheduler.class);

    private final BidRepository bidRepository;
    private final PaymentService paymentService;

    public AwaitingPaymentCleanupScheduler(BidRepository bidRepository,
                                           PaymentService paymentService) {
        this.bidRepository = bidRepository;
        this.paymentService = paymentService;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void cleanupUnpaidBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BidEntity> expired = bidRepository
            .findByStatusAndAwaitingPaymentExpiresAtBefore(BidStatus.AWAITING_PAYMENT, now);

        for (BidEntity bid : expired) {
            String piId = bid.getPaymentIntentId();
            try {
                paymentService.cancelPaymentIntent(piId);
                bidRepository.deleteById(bid.getId());
                log.info("Bid {} (PI={}) deleted (unpaid timeout)", bid.getId(), piId);
            } catch (StripeException e) {
                if ("payment_intent_unexpected_state".equals(e.getCode()) && isAlreadyCapturedOrSucceeded(piId)) {
                    log.warn("Race condition for bid {}: PI succeeded — promoting to PENDING", bid.getId());
                    paymentService.promoteBidOnPaymentAuthorized(piId);
                } else {
                    log.error("Cleanup failed for bid {} (PI={}): {}",
                        bid.getId(), piId, e.getMessage());
                    // Do not delete — retry next tick
                }
            }
        }
    }

    private boolean isAlreadyCapturedOrSucceeded(String paymentIntentId) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
            String status = pi.getStatus();
            return "succeeded".equals(status)
                || "requires_capture".equals(status)
                || "processing".equals(status);
        } catch (StripeException e) {
            log.warn("Could not retrieve PI {} for race-condition check: {}", paymentIntentId, e.getMessage());
            return false;
        }
    }
}
