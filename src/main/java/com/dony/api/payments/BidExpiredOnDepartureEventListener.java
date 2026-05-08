package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidExpiredOnDepartureEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Quand un bid passe en EXPIRED au départ du voyageur :
 * - Si le paiement est PENDING (PI autorisé, pas encore capturé) → annuler le PI.
 * - Si le paiement est ESCROW → rembourser via Stripe Refund.
 * - Autres statuts → rien à faire.
 */
@Component
public class BidExpiredOnDepartureEventListener {

    private static final Logger log = LoggerFactory.getLogger(BidExpiredOnDepartureEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public BidExpiredOnDepartureEventListener(PaymentRepository paymentRepository,
                                              AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    @EventListener
    @Async
    @Transactional
    public void handleBidExpired(BidExpiredOnDepartureEvent event) {
        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(event.getBidId());
        if (paymentOpt.isEmpty()) {
            log.debug("Aucun paiement pour bid expiré {} — rien à rembourser", event.getBidId());
            return;
        }

        PaymentEntity payment = paymentOpt.get();

        switch (payment.getStatus()) {
            case PENDING -> cancelPendingPaymentIntent(payment);
            case ESCROW -> refundEscrowedPayment(payment);
            default -> log.info("Paiement {} en statut {} — aucune action nécessaire",
                    payment.getId(), payment.getStatus());
        }
    }

    private void cancelPendingPaymentIntent(PaymentEntity payment) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.cancel(PaymentIntentCancelParams.builder()
                    .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                    .build());

            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_CANCELLED_BID_EXPIRED",
                    payment.getBidId(),
                    Map.of("piId", payment.getStripePaymentIntentId(),
                           "reason", "bid_expired_traveler_departed"));

            log.info("PaymentIntent {} annulé (bid expiré au départ)", payment.getStripePaymentIntentId());

        } catch (StripeException e) {
            log.error("Échec annulation PI {} : {}", payment.getStripePaymentIntentId(), e.getMessage(), e);
        }
    }

    private void refundEscrowedPayment(PaymentEntity payment) {
        try {
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(payment.getStripePaymentIntentId())
                    .build());

            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_REFUNDED_BID_EXPIRED",
                    payment.getBidId(),
                    Map.of("piId", payment.getStripePaymentIntentId(),
                           "amount", payment.getAmount().toPlainString(),
                           "reason", "bid_expired_traveler_departed"));

            log.info("Remboursement émis pour PI {} (bid expiré au départ)", payment.getStripePaymentIntentId());

        } catch (StripeException e) {
            log.error("Échec remboursement PI {} : {}", payment.getStripePaymentIntentId(), e.getMessage(), e);
        }
    }
}
