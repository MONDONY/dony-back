package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.payments.events.PaymentEscrowReadyEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Map;

/**
 * Negotiation / dedicated-trip Stripe escrow — capture onto the platform balance.
 *
 * <p>The classic bid flow captures the manual-capture {@code PaymentIntent} at acceptance
 * ({@link BidAcceptedEventListener}) and only transfers to the traveler at delivery
 * ({@link DeliveryEventListener}). The negotiation flow stores its escrow payment on the
 * negotiation thread ({@code bid_id = NULL}) and never published a {@code BidAcceptedEvent},
 * so the capture step never ran — the PaymentIntent stayed an authorization hold that
 * expires (~7 days) and the money never reached the traveler.
 *
 * <p><b>Trigger.</b> This listens to {@link PaymentEscrowReadyEvent}, which is published by
 * {@code PaymentService.handlePaymentEscrowActive} at the exact moment a payment transitions
 * {@code PENDING → ESCROW} (the Stripe {@code amount_capturable_updated} webhook = funds held,
 * PI {@code requires_capture}). We deliberately do NOT key off {@code PackageRequestAcceptedEvent}:
 * the synchronous {@code /checkout} can publish that BEFORE the webhook flips the payment to
 * ESCROW (observed ~7s earlier), so the capture would race and be skipped.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} guarantees the ESCROW status is committed
 * before we run.
 *
 * <p>Only thread-keyed payments are handled here ({@code negotiation_thread_id} set,
 * {@code bid_id} null). Classic bid payments emit the same event but are captured by
 * {@link BidAcceptedEventListener}; we skip them to avoid a double capture.
 *
 * <p>After capture the payment stays {@code ESCROW} (only {@code captured_at} is set), so
 * {@link DeliveryEventListener} still transfers it to the traveler at delivery — with no
 * card-authorization expiry, since the funds already left the card at acceptance.
 */
@Component
public class NegotiationCaptureListener {

    private static final Logger log = LoggerFactory.getLogger(NegotiationCaptureListener.class);

    /** Manual-capture PaymentIntent state where the card is authorized and funds are held. */
    private static final String STATUS_REQUIRES_CAPTURE = "requires_capture";

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public NegotiationCaptureListener(PaymentRepository paymentRepository,
                                      AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEscrowReady(PaymentEscrowReadyEvent event) {
        PaymentEntity payment = paymentRepository.findById(event.getPaymentId()).orElse(null);
        if (payment == null) {
            return;
        }

        // Only the negotiation/dedicated-trip escrow (keyed on the thread) is captured here.
        // Classic bid payments (bid_id set) are captured at acceptance by BidAcceptedEventListener.
        if (payment.getNegotiationThreadId() == null || payment.getBidId() != null) {
            return;
        }
        if (payment.isLegacyDestinationCharge()) {
            // Legacy destination-charge model captures at delivery — leave it untouched.
            return;
        }
        if (payment.getStatus() != PaymentStatus.ESCROW) {
            // Not (yet) a held escrow — nothing to capture.
            return;
        }

        try {
            // Atomic capture-once guard: sets captured_at, keeps the status ESCROW so the
            // delivery release (which requires status==ESCROW) still fires later.
            int updated = paymentRepository.markCapturedIfEscrow(payment.getId(), Instant.now());
            if (updated == 0) {
                log.info("Negotiation payment {} already captured — skipping", payment.getId());
                return;
            }

            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            // Idempotent: only an authorized manual-capture hold can be captured. A PI already
            // captured (succeeded) — e.g. an admin/manual capture — is left as-is.
            if (STATUS_REQUIRES_CAPTURE.equals(pi.getStatus())) {
                pi.capture();
            }
            // Persist the Stripe charge id for the delivery-time Transfer.sourceTransaction.
            if (payment.getStripeChargeId() == null && pi.getLatestCharge() != null) {
                payment.setStripeChargeId(pi.getLatestCharge());
                paymentRepository.save(payment);
            }

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_CAPTURED_ON_PLATFORM", null,
                    Map.of("piId", payment.getStripePaymentIntentId(),
                            "threadId", String.valueOf(payment.getNegotiationThreadId()),
                            "source", "negotiation-escrow-ready"));
            log.info("Negotiation PI {} captured on platform for thread {}",
                    payment.getStripePaymentIntentId(), payment.getNegotiationThreadId());

        } catch (StripeException ex) {
            log.error("Negotiation capture failed (thread={}, pi={}): {}",
                    payment.getNegotiationThreadId(), payment.getStripePaymentIntentId(),
                    ex.getMessage(), ex);
            // Not rethrown — the delivery release path and the admin J+48 force-release remain backstops.
        }
    }
}
