package com.dony.api.payments;

import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Sur acceptation d'un bid par le voyageur :
 * - Pour les paiements non-legacy (separate charges and transfers) → re-vérifie l'éligibilité
 *   du voyageur puis capture le PaymentIntent sur le compte plateforme. L'argent reste là
 *   jusqu'à confirmation de livraison (où DeliveryEventListener fera Transfer.create).
 * - Pour les paiements legacy (transfer_data.destination déjà posé) → ne rien faire ici.
 *   La capture historique reste à la livraison.
 */
@Component
public class BidAcceptedEventListener {

    private static final Logger log = LoggerFactory.getLogger(BidAcceptedEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;

    public BidAcceptedEventListener(PaymentRepository paymentRepository,
                                    AuditService auditService,
                                    UserRepository userRepository,
                                    BidRepository bidRepository) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidAccepted(BidAcceptedEvent event) {
        Optional<PaymentEntity> opt = paymentRepository.findByBidId(event.getBidId());
        if (opt.isEmpty()) {
            log.warn("BidAccepted but no payment found for bid {}", event.getBidId());
            return;
        }
        PaymentEntity payment = opt.get();

        if (payment.isLegacyDestinationCharge()) {
            log.info("Bid {} accepted but payment is legacy — capture deferred to delivery",
                    event.getBidId());
            return;
        }

        if (payment.getStatus() != PaymentStatus.ESCROW) {
            log.info("Bid {} accepted but payment status is {} — skipping capture",
                    event.getBidId(), payment.getStatus());
            return;
        }

        // Re-verify traveler eligibility before capture (status may have changed since PI creation)
        UserEntity traveler = userRepository.findById(event.getTravelerId()).orElse(null);
        if (traveler == null || traveler.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE) {
            log.warn("Bid {} cancelled: traveler {} lost Connect eligibility before capture",
                    event.getBidId(), event.getTravelerId());
            String piId = payment.getStripePaymentIntentId();
            try {
                PaymentIntent pi = PaymentIntent.retrieve(piId);
                pi.cancel();
                log.info("PaymentIntent {} cancelled due to traveler ineligibility (bid {})",
                        piId, event.getBidId());

                // Fix 1: update PaymentEntity so it no longer stays in ESCROW
                payment.setStatus(PaymentStatus.CANCELLED);
                paymentRepository.save(payment);

                bidRepository.findById(event.getBidId()).ifPresent(bid -> {
                    bid.setStatus(BidStatus.CANCELLED);
                    bidRepository.save(bid);
                    auditService.log("BID", bid.getId(), "BID_CANCELLED_TRAVELER_INELIGIBLE",
                            event.getTravelerId(),
                            Map.of("reason", "traveler-connect-ineligible",
                                    "piId", piId));
                });
            } catch (StripeException cancelEx) {
                // Fix 2: write audit log so ops can reconcile the live PI manually
                log.error("Could not cancel PI {} for ineligible traveler {} on bid {}",
                        piId, event.getTravelerId(), event.getBidId(), cancelEx);
                bidRepository.findById(event.getBidId()).ifPresent(bid ->
                        auditService.log("BID", bid.getId(), "BID_CANCEL_PI_FAILED",
                                event.getTravelerId(),
                                Map.of("pi_id", piId,
                                        "traveler_id", event.getTravelerId().toString(),
                                        "reason", "traveler_not_eligible_pi_cancel_failed"))
                );
            }
            return;
        }

        try {
            // Atomic capture-once guard: prevents double-capture on duplicate events
            int updated = paymentRepository.markCapturedIfEscrow(payment.getId(), Instant.now());
            if (updated == 0) {
                log.info("Payment {} already captured or not in ESCROW — skipping", payment.getId());
                return;
            }

            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.capture();
            // Note: payment.status reste ESCROW. Sa sémantique est désormais
            // "captured on platform, awaiting delivery" pour les non-legacy.

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_CAPTURED_ON_PLATFORM",
                    payment.getBidId(),
                    Map.of("piId", payment.getStripePaymentIntentId(),
                            "bidId", event.getBidId().toString()));

            log.info("PaymentIntent {} captured on platform for bid {}",
                    payment.getStripePaymentIntentId(), event.getBidId());

        } catch (StripeException e) {
            log.error("Capture failed for bid {} (PI={}): {}",
                    event.getBidId(), payment.getStripePaymentIntentId(),
                    e.getMessage(), e);
            // Sentry will catch ; admin J+48 scheduler can recover
        }
    }
}
