package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.common.stripe.AdminAlertService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chemin unique de remboursement d'un paiement (bid classique ou thread de négociation).
 *
 * <p>Garanties :
 * <ul>
 *   <li>PENDING → le PaymentIntent est annulé (un PI non capturé ne se rembourse pas) ;</li>
 *   <li>ESCROW → claim atomique {@code markRefundedIfEscrow} (anti double-refund intra-instance)
 *       puis {@code Refund.create} avec clé d'idempotence {@code "refund-" + paymentId}
 *       (anti double-refund inter-instances, déduplication côté Stripe) ;</li>
 *   <li>échec Stripe → alerte admin + exception : la transaction REQUIRES_NEW rollback le claim,
 *       le paiement reste remboursable ;</li>
 *   <li>RELEASED / REFUNDED / FAILED / CANCELLED → no-op (jamais de refund post-versement).</li>
 * </ul>
 *
 * <p>{@code REQUIRES_NEW} : chaque paiement vit dans sa propre transaction — un échec dans un
 * traitement par lot (annulation de trajet) n'annule pas les remboursements déjà réussis.
 */
@Component
public class RefundProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlert;

    public RefundProcessor(PaymentRepository paymentRepository,
                           AuditService auditService,
                           AdminAlertService adminAlert) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.adminAlert = adminAlert;
    }

    /**
     * @return true si une action Stripe a été exécutée (cancel ou refund), false si no-op.
     * @throws IllegalStateException si Stripe échoue sur le refund ESCROW (rollback du claim).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processRefund(UUID paymentId, String auditAction, UUID auditActor,
                                 Map<String, String> auditPayload) {
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.debug("processRefund: payment {} introuvable — no-op", paymentId);
            return false;
        }

        return switch (payment.getStatus()) {
            case PENDING -> cancelPendingPaymentIntent(payment, auditAction, auditActor, auditPayload);
            case ESCROW -> refundEscrowedPayment(payment, paymentId, auditAction, auditActor, auditPayload);
            default -> {
                log.info("processRefund: payment {} en statut {} — aucune action",
                        payment.getId(), payment.getStatus());
                yield false;
            }
        };
    }

    private boolean cancelPendingPaymentIntent(PaymentEntity payment, String auditAction,
                                               UUID auditActor, Map<String, String> auditPayload) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.cancel(PaymentIntentCancelParams.builder()
                    .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                    .build());

            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), auditAction, auditActor,
                    enrich(auditPayload, payment));
            log.info("PaymentIntent {} annulé ({})", payment.getStripePaymentIntentId(), auditAction);
            return true;
        } catch (StripeException e) {
            log.error("Échec annulation PI {} : {}", payment.getStripePaymentIntentId(), e.getMessage(), e);
            return false;
        }
    }

    private boolean refundEscrowedPayment(PaymentEntity payment, UUID paymentId, String auditAction,
                                          UUID auditActor, Map<String, String> auditPayload) {
        int claimed = paymentRepository.markRefundedIfEscrow(paymentId);
        if (claimed == 0) {
            log.info("Paiement {} déjà sorti d'ESCROW — remboursement ignoré", paymentId);
            return false;
        }

        try {
            Refund.create(
                    RefundCreateParams.builder()
                            .setPaymentIntent(payment.getStripePaymentIntentId())
                            .build(),
                    RequestOptions.builder()
                            .setIdempotencyKey("refund-" + paymentId)
                            .build());
        } catch (StripeException e) {
            log.error("Échec remboursement PI {} : {}",
                    payment.getStripePaymentIntentId(), e.getMessage(), e);
            Map<String, Object> alertCtx = new HashMap<>();
            alertCtx.put("paymentId", paymentId.toString());
            alertCtx.put("piId", payment.getStripePaymentIntentId());
            alertCtx.put("error", String.valueOf(e.getMessage()));
            adminAlert.raise("STRIPE_REFUND_FAILED",
                    "Remboursement Stripe échoué pour payment " + paymentId,
                    alertCtx);
            throw new IllegalStateException("Stripe refund failed for payment " + paymentId, e);
        }

        auditService.log("PAYMENT", payment.getId(), auditAction, auditActor,
                enrich(auditPayload, payment));
        log.info("Remboursement émis pour PI {} ({})", payment.getStripePaymentIntentId(), auditAction);
        return true;
    }

    private Map<String, Object> enrich(Map<String, String> payload, PaymentEntity payment) {
        Map<String, Object> out = new HashMap<>(payload);
        out.put("piId", payment.getStripePaymentIntentId());
        out.put("amount", payment.getAmount().toPlainString());
        return out;
    }
}
