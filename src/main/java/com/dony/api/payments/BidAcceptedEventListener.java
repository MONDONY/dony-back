package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Sur acceptation d'un bid par le voyageur :
 * - Pour les paiements non-legacy (separate charges and transfers) → capture le PaymentIntent
 *   sur le compte plateforme. L'argent reste là jusqu'à confirmation de livraison
 *   (où DeliveryEventListener fera Transfer.create vers le voyageur).
 * - Pour les paiements legacy (transfer_data.destination déjà posé) → ne rien faire ici.
 *   La capture historique reste à la livraison.
 */
@Component
public class BidAcceptedEventListener {

    private static final Logger log = LoggerFactory.getLogger(BidAcceptedEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public BidAcceptedEventListener(PaymentRepository paymentRepository,
                                    AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    @EventListener
    @Async
    @Transactional
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

        try {
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
