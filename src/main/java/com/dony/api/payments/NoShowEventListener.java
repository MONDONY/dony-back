package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.VoyageurNoShowEvent;
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

    @EventListener
    @Async
    @Transactional
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

        try {
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(payment.getStripePaymentIntentId())
                    .build());

            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_REFUNDED_NO_SHOW",
                    event.getTravelerId(),
                    Map.of("bidId", event.getBidId().toString(),
                            "piId", payment.getStripePaymentIntentId()));

            log.info("Escrow refunded for no-show bid={}", event.getBidId());

        } catch (StripeException e) {
            log.error("Failed to refund escrow for no-show bid={}: {}", event.getBidId(), e.getMessage(), e);
        }
    }
}
