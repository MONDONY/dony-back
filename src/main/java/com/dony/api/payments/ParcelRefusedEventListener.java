package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.ParcelRefusedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

// Story 9.4 — Refund escrow automatically when traveler refuses parcel
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

    @EventListener
    @Async
    @Transactional
    public void onParcelRefused(ParcelRefusedEvent event) {
        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(event.getBidId());
        if (paymentOpt.isEmpty()) {
            log.debug("No payment found for refused parcel bid={} — nothing to refund", event.getBidId());
            return;
        }

        PaymentEntity payment = paymentOpt.get();
        if (payment.getStatus() == PaymentStatus.REFUNDED || payment.getStatus() == PaymentStatus.FAILED) {
            log.info("Payment {} already refunded/cancelled for bid={}", payment.getId(), event.getBidId());
            return;
        }

        try {
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(payment.getStripePaymentIntentId())
                    .build());

            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_REFUNDED_PARCEL_REFUSED",
                    event.getTravelerId(),
                    Map.of("bidId", event.getBidId().toString(),
                            "piId", payment.getStripePaymentIntentId(),
                            "reason", event.getReason() != null ? event.getReason() : ""));

            log.info("Escrow refunded for refused parcel bid={}", event.getBidId());

        } catch (StripeException e) {
            log.error("Failed to refund escrow for refused parcel bid={}: {}", event.getBidId(), e.getMessage(), e);
        }
    }
}
