package com.dony.api.payments;

import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
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
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

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
    private final com.dony.api.matching.BidRepository bidRepository;

    public TripCancelledEventListener(PaymentRepository paymentRepository,
                                      AuditService auditService,
                                      com.dony.api.matching.BidRepository bidRepository) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.bidRepository = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        // Negotiation / dedicated-trip escrow is keyed on the negotiation thread (bid_id NULL).
        // Fall back to the thread payment so the sender of a cancelled negotiation trip is
        // refunded too; otherwise findByBidId returns empty and the refund is silently skipped.
        if (paymentOpt.isEmpty()) {
            paymentOpt = bidRepository.findById(bidId)
                    .map(BidEntity::getLinkedNegotiationThreadId)
                    .filter(java.util.Objects::nonNull)
                    .flatMap(paymentRepository::findByNegotiationThreadId);
        }

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
                            bidId,
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
                            bidId,
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
