package com.dony.api.payments;

import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.common.AuditService;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 6.7 — Listens to TripCancelledEvent and issues Stripe refunds for any
 * bid that had an ESCROW payment. PENDING payments are skipped (card not yet charged).
 *
 * Failures for individual bids are logged but do not abort the other refunds.
 */
@Component
public class TripCancelledEventListener {

    private static final Logger log = LoggerFactory.getLogger(TripCancelledEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public TripCancelledEventListener(PaymentRepository paymentRepository,
                                      AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    @EventListener
    @Async
    @Transactional
    public void handleTripCancelled(TripCancelledEvent event) {
        List<UUID> bidIds = event.getAffectedBidIds();
        if (bidIds == null || bidIds.isEmpty()) {
            return;
        }

        log.info("TripCancelledEvent received for announcement={}, processing {} bid(s) for refund",
                event.getAnnouncementId(), bidIds.size());

        for (UUID bidId : bidIds) {
            processRefundForBid(bidId);
        }
    }

    private void processRefundForBid(UUID bidId) {
        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(bidId);

        if (paymentOpt.isEmpty()) {
            log.debug("No payment found for bid {} — skipping refund", bidId);
            return;
        }

        PaymentEntity payment = paymentOpt.get();

        try {
            switch (payment.getStatus()) {
                case PENDING -> {
                    // Carte non encore débitée → annuler le PaymentIntent
                    PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
                    pi.cancel(PaymentIntentCancelParams.builder()
                            .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                            .build());
                    payment.setStatus(PaymentStatus.REFUNDED);
                    paymentRepository.save(payment);
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_CANCELLED_TRIP_CANCELLED",
                            payment.getBidId(),
                            Map.of("bidId", bidId.toString(),
                                    "piId", payment.getStripePaymentIntentId(),
                                    "reason", "trip_cancelled_before_authorization"));
                    log.info("PaymentIntent {} annulé (trip cancelled, bid={})", payment.getStripePaymentIntentId(), bidId);
                }
                case ESCROW -> {
                    // Carte déjà débitée → remboursement Stripe
                    Refund.create(RefundCreateParams.builder()
                            .setPaymentIntent(payment.getStripePaymentIntentId())
                            .build());
                    payment.setStatus(PaymentStatus.REFUNDED);
                    paymentRepository.save(payment);
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_REFUNDED",
                            payment.getBidId(),
                            Map.of("bidId", bidId.toString(),
                                    "piId", payment.getStripePaymentIntentId(),
                                    "amount", payment.getAmount().toPlainString(),
                                    "reason", "trip_cancelled"));
                    log.info("Remboursement émis pour payment {} (bid={}, PI={})",
                            payment.getId(), bidId, payment.getStripePaymentIntentId());
                }
                default -> log.info("Payment {} (bid={}) en statut {} — aucune action",
                        payment.getId(), bidId, payment.getStatus());
            }
        } catch (StripeException e) {
            log.error("Stripe error pour payment {} (bid={}, PI={}): {}",
                    payment.getId(), bidId, payment.getStripePaymentIntentId(), e.getMessage(), e);
            // Ne pas rethrow — les autres bids du même event doivent être traités
        }
    }
}
