package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Story 6.4 — Listens to DeliveryConfirmedEvent and captures the Stripe escrow.
 * Cross-package communication via Spring Events only (no direct PaymentService injection
 * into TrackingService).
 */
@Component
public class DeliveryEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public DeliveryEventListener(PaymentRepository paymentRepository,
                                 AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    @EventListener
    @Async
    @Transactional
    public void handleDeliveryConfirmed(DeliveryConfirmedEvent event) {
        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(event.getBidId());

        if (paymentOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent received for bidId={} but no payment found — skipping",
                    event.getBidId());
            return;
        }

        PaymentEntity payment = paymentOpt.get();

        if (payment.getStatus() != PaymentStatus.ESCROW) {
            log.info("Payment {} for bid {} has status {} — skipping escrow capture",
                    payment.getId(), event.getBidId(), payment.getStatus());
            return;
        }

        try {
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.capture();

            payment.setStatus(PaymentStatus.RELEASED);
            payment.setEscrowReleasedAt(LocalDateTime.now(ZoneOffset.UTC));
            paymentRepository.save(payment);

            auditService.log(
                    "PAYMENT",
                    payment.getId(),
                    "ESCROW_RELEASED",
                    payment.getBidId(),
                    Map.of(
                            "bidId", payment.getBidId().toString(),
                            "piId", payment.getStripePaymentIntentId(),
                            "amount", payment.getAmount().toPlainString()
                    )
            );

            log.info("Escrow released for payment {} (bid={}, PI={})",
                    payment.getId(), event.getBidId(), payment.getStripePaymentIntentId());

        } catch (StripeException e) {
            log.error("Stripe capture failed for payment {} (bid={}, PI={}): {}",
                    payment.getId(), event.getBidId(), payment.getStripePaymentIntentId(),
                    e.getMessage(), e);
            // Do not rethrow — failure is logged, admin J+48 scheduler will catch it
        }
    }
}
