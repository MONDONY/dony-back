package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
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

// Story 9.6 — Refund escrow when traveler is a no-show
@Component
public class NoShowEventListener {

    private static final Logger log = LoggerFactory.getLogger(NoShowEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public NoShowEventListener(PaymentRepository paymentRepository, AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVoyageurNoShow(VoyageurNoShowEvent event) {
        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(event.getBidId());
        if (paymentOpt.isEmpty()) {
            log.debug("No payment found for no-show bid={}", event.getBidId());
            return;
        }

        PaymentEntity payment = paymentOpt.get();
        if (payment.getStatus() != PaymentStatus.ESCROW) {
            return;
        }

        // Claim atomique ESCROW → REFUNDED avant l'appel Stripe : empêche un double
        // remboursement si un autre listener (annulation, rejet…) traite le même paiement.
        int claimed = paymentRepository.markRefundedIfEscrow(payment.getId());
        if (claimed == 0) {
            log.info("Paiement {} déjà sorti d'ESCROW — remboursement ignoré (no-show)",
                    payment.getId());
            return;
        }

        try {
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(payment.getStripePaymentIntentId())
                    .build());
        } catch (StripeException e) {
            log.error("Failed to refund escrow for no-show bid={}: {}",
                    event.getBidId(), e.getMessage(), e);
            // Rollback de la transaction REQUIRES_NEW : le claim ESCROW → REFUNDED est
            // annulé et le paiement reste remboursable (backstop admin).
            throw new IllegalStateException(
                    "Stripe refund failed for payment " + payment.getId(), e);
        }

        auditService.log("PAYMENT", payment.getId(), "PAYMENT_REFUNDED_NO_SHOW",
                event.getTravelerId(),
                Map.of("bidId", event.getBidId().toString(),
                        "piId", payment.getStripePaymentIntentId()));

        log.info("Escrow refunded for no-show bid={}", event.getBidId());
    }
}
