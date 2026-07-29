package com.yadony.api.payments;

import com.yadony.api.requests.event.NegotiationCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Cancels the in-flight Stripe escrow (card hold) when a negotiation is ended
 * before payment while it was AWAITING_PAYMENT.
 *
 * <p><b>Why AFTER_COMMIT.</b> Cancelling a PaymentIntent at Stripe
 * ({@code pi.cancel()}) is a non-transactional, irreversible side-effect. If it
 * ran inline inside {@link com.yadony.api.requests.service.NegotiationService#cancelNegotiation}
 * (before the {@code CANCELLED} status is committed) any subsequent rollback —
 * a CHECK-constraint failure, a concurrent checkout/webhook finalize winning the
 * {@code @Version} race — would void the hold for good while the DB reverts the
 * cancellation, leaking the card authorization. Per CLAUDE.md rule #18 the Stripe
 * side-effect is therefore deferred to {@code AFTER_COMMIT}: it only fires once the
 * cancel transaction actually committed, and a rollback fires no AFTER_COMMIT at all.
 *
 * <p>{@code REQUIRES_NEW} gives the release its own transaction (the original one is
 * already committed by the time this runs). Only threads that were AWAITING_PAYMENT
 * carry {@code releaseEscrow == true}; OPEN / AWAITING_TRIP cancels have no hold and
 * are skipped.
 */
@Component
public class NegotiationCancelledEscrowListener {

    private static final Logger log =
        LoggerFactory.getLogger(NegotiationCancelledEscrowListener.class);

    private final PaymentService paymentService;

    public NegotiationCancelledEscrowListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, condition = "#event.releaseEscrow()")
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNegotiationCancelled(NegotiationCancelledEvent event) {
        // Idempotent, best-effort: cancelNegotiationEscrow is a no-op when no escrow
        // exists or it is already terminal, and returns false only if a live hold could
        // not be cancelled (already captured) — logged, not rethrown, so a Stripe error
        // never leaves the (already committed) cancellation half-done.
        try {
            boolean released = paymentService.cancelNegotiationEscrow(event.threadId(), "negotiation-cancelled");
            if (!released) {
                log.warn("Negotiation escrow could not be released after cancel (thread={})",
                    event.threadId());
            }
        } catch (RuntimeException ex) {
            log.error("Failed to release negotiation escrow after cancel (thread={})",
                event.threadId(), ex);
        }
    }
}
