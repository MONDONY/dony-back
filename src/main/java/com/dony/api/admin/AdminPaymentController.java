package com.dony.api.admin;

import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.PaymentEntity;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import com.dony.api.payments.dto.PaymentResponse;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 6.5 — Admin-only endpoints for manual escrow operations.
 * All endpoints require ROLE_ADMIN.
 */
@RestController
@RequestMapping("/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentController {

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentController.class);

    private final PaymentRepository paymentRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final AuditService auditService;

    public AdminPaymentController(PaymentRepository paymentRepository,
                                  AdminAlertRepository adminAlertRepository,
                                  AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.adminAlertRepository = adminAlertRepository;
        this.auditService = auditService;
    }

    /**
     * POST /admin/payments/{id}/force-release
     * Manually captures the Stripe PaymentIntent and releases the escrow.
     * Only allowed when payment status is ESCROW.
     * Marks any related ESCROW_J48_TIMEOUT alert as resolved.
     */
    @PostMapping("/{id}/force-release")
    @Transactional
    public ResponseEntity<PaymentResponse> forceRelease(@PathVariable UUID id) {
        PaymentEntity payment = paymentRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found",
                        "Paiement introuvable"));

        if (payment.getStatus() != PaymentStatus.ESCROW) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-in-escrow",
                    "Invalid Status",
                    "Seuls les paiements en statut ESCROW peuvent faire l'objet d'une libération forcée");
        }

        try {
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.capture();
        } catch (StripeException e) {
            log.error("Admin force-release: Stripe capture failed for payment {} (PI={}): {}",
                    id, payment.getStripePaymentIntentId(), e.getMessage(), e);
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "stripe-capture-failed",
                    "Stripe Error",
                    "Impossible de capturer le paiement Stripe. Veuillez réessayer.");
        }

        payment.setStatus(PaymentStatus.RELEASED);
        payment.setEscrowReleasedAt(LocalDateTime.now(ZoneOffset.UTC));
        paymentRepository.save(payment);

        // Resolve any open ESCROW_J48_TIMEOUT alerts for this payment
        resolveRelatedAlerts(id);

        auditService.log(
                "PAYMENT",
                payment.getId(),
                "ESCROW_FORCE_RELEASED",
                payment.getBidId(),
                Map.of(
                        "paymentId", id.toString(),
                        "bidId", payment.getBidId().toString(),
                        "piId", payment.getStripePaymentIntentId(),
                        "amount", payment.getAmount().toPlainString()
                )
        );

        log.info("Admin force-released escrow for payment {} (bid={}, PI={})",
                id, payment.getBidId(), payment.getStripePaymentIntentId());

        return ResponseEntity.ok(toResponse(payment));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void resolveRelatedAlerts(UUID paymentId) {
        String paymentIdStr = paymentId.toString();
        List<AdminAlertEntity> alerts =
                adminAlertRepository.findByTypeAndResolved("ESCROW_J48_TIMEOUT", false);

        alerts.stream()
                .filter(a -> a.getPayload() != null && a.getPayload().contains(paymentIdStr))
                .forEach(a -> {
                    a.setResolved(true);
                    adminAlertRepository.save(a);
                });
    }

    private PaymentResponse toResponse(PaymentEntity payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBidId(),
                null,
                payment.getAmount(),
                payment.getCommissionAmount(),
                payment.getStatus().name()
        );
    }
}
