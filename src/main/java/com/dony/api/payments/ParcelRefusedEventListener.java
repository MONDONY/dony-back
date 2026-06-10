package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.ParcelRefusedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Optional;

/**
 * Story 9.4 — Quand un voyageur refuse un colis :
 * - Si le paiement est PENDING (PI non encore autorisé/capturé) → annuler le PaymentIntent.
 * - Si le paiement est ESCROW (fonds bloqués) → rembourser via Stripe Refund.
 * - Autres statuts (RELEASED, REFUNDED, FAILED, CANCELLED) → rien à faire. En
 *   particulier un paiement RELEASED (fonds déjà transférés au voyageur) ne doit
 *   JAMAIS être remboursé ici — double sortie d'argent sinon.
 */
@Component
public class ParcelRefusedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ParcelRefusedEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public ParcelRefusedEventListener(PaymentRepository paymentRepository,
                                      AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onParcelRefused(ParcelRefusedEvent event) {
        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(event.getBidId());
        if (paymentOpt.isEmpty()) {
            log.debug("No payment found for refused parcel bid={} — nothing to refund", event.getBidId());
            return;
        }

        PaymentEntity payment = paymentOpt.get();

        switch (payment.getStatus()) {
            case PENDING -> cancelPendingPaymentIntent(payment, event);
            case ESCROW -> refundEscrowedPayment(payment, event);
            default -> log.info("Payment {} (bid={}) en statut {} — aucune action nécessaire",
                    payment.getId(), event.getBidId(), payment.getStatus());
        }
    }

    private void cancelPendingPaymentIntent(PaymentEntity payment, ParcelRefusedEvent event) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.cancel(PaymentIntentCancelParams.builder()
                    .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                    .build());

            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_CANCELLED_PARCEL_REFUSED",
                    event.getTravelerId(),
                    Map.of("bidId", event.getBidId().toString(),
                            "piId", payment.getStripePaymentIntentId(),
                            "reason", event.getReason() != null ? event.getReason() : ""));

            log.info("PaymentIntent {} annulé (colis refusé, paiement non autorisé)",
                    payment.getStripePaymentIntentId());

        } catch (StripeException e) {
            log.error("Échec annulation PI {} (colis refusé) : {}",
                    payment.getStripePaymentIntentId(), e.getMessage(), e);
        }
    }

    private void refundEscrowedPayment(PaymentEntity payment, ParcelRefusedEvent event) {
        // Claim atomique ESCROW → REFUNDED avant l'appel Stripe : empêche un double
        // remboursement si un autre listener (annulation, no-show…) traite le même paiement.
        int claimed = paymentRepository.markRefundedIfEscrow(payment.getId());
        if (claimed == 0) {
            log.info("Paiement {} déjà sorti d'ESCROW — remboursement ignoré (colis refusé)",
                    payment.getId());
            return;
        }

        try {
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(payment.getStripePaymentIntentId())
                    .build());
        } catch (StripeException e) {
            log.error("Failed to refund escrow for refused parcel bid={}: {}",
                    event.getBidId(), e.getMessage(), e);
            // Rollback de la transaction REQUIRES_NEW : le claim ESCROW → REFUNDED est
            // annulé et le paiement reste remboursable (backstop admin).
            throw new IllegalStateException(
                    "Stripe refund failed for payment " + payment.getId(), e);
        }

        auditService.log("PAYMENT", payment.getId(), "PAYMENT_REFUNDED_PARCEL_REFUSED",
                event.getTravelerId(),
                Map.of("bidId", event.getBidId().toString(),
                        "piId", payment.getStripePaymentIntentId(),
                        "reason", event.getReason() != null ? event.getReason() : ""));

        log.info("Escrow refunded for refused parcel bid={}", event.getBidId());
    }
}
