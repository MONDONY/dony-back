package com.dony.api.requests.service;

import com.dony.api.payments.NegotiationPaymentAuthorizedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges Stripe webhook → NegotiationService.finalizeAfterPayment when the
 * negotiation PaymentIntent reaches escrow-active state.
 *
 * Uses {@code AFTER_COMMIT} + {@code REQUIRES_NEW} per project security rule
 * (see CLAUDE.md): listener must not read uncommitted webhook tx state.
 */
@Component
public class NegotiationPaymentListener {

    private static final Logger log =
        LoggerFactory.getLogger(NegotiationPaymentListener.class);

    private final NegotiationService negotiationService;

    public NegotiationPaymentListener(NegotiationService negotiationService) {
        this.negotiationService = negotiationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaymentAuthorized(NegotiationPaymentAuthorizedEvent e) {
        log.info("NegotiationPaymentAuthorizedEvent: threadId={} pi={}",
            e.threadId(), e.paymentIntentId());
        try {
            negotiationService.finalizeAfterPayment(
                e.senderId(), e.threadId(), e.paymentIntentId());
        } catch (Exception ex) {
            log.error("Failed to finalize negotiation thread {} after payment {}",
                e.threadId(), e.paymentIntentId(), ex);
        }
    }
}
