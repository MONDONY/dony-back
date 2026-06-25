package com.dony.api.admin;

import com.dony.api.admin.dto.AdminChargebackResponse;
import com.dony.api.admin.dto.AdminPaymentDetailResponse;
import com.dony.api.admin.dto.AdminPaymentListItemResponse;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.payments.PaymentEntity;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import com.dony.api.payments.chargeback.ChargebackRepository;
import com.dony.api.payments.dto.PaymentResponse;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.TransferCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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

    /** Manual-capture PaymentIntent state where the card is authorized and funds are held. */
    private static final String STATUS_REQUIRES_CAPTURE = "requires_capture";

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentController.class);

    private final PaymentRepository paymentRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final AuditService auditService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ChargebackRepository chargebackRepository;

    public AdminPaymentController(PaymentRepository paymentRepository,
                                  AdminAlertRepository adminAlertRepository,
                                  AuditService auditService,
                                  BidRepository bidRepository,
                                  AnnouncementRepository announcementRepository,
                                  UserRepository userRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  ChargebackRepository chargebackRepository) {
        this.paymentRepository = paymentRepository;
        this.adminAlertRepository = adminAlertRepository;
        this.auditService = auditService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.chargebackRepository = chargebackRepository;
    }

    @GetMapping
    public ResponseEntity<Page<AdminPaymentListItemResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PaymentEntity> raw = paymentRepository.findAdminFiltered(status, PageRequest.of(page, size));
        return ResponseEntity.ok(raw.map(AdminPaymentListItemResponse::from));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminPaymentDetailResponse> getById(@PathVariable UUID id) {
        PaymentEntity p = paymentRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found", "Paiement introuvable"));
        return ResponseEntity.ok(AdminPaymentDetailResponse.from(p));
    }

    /**
     * POST /admin/payments/{id}/force-release
     * Manually releases the Stripe escrow to the traveler. Only allowed when status is ESCROW.
     *
     * <p>Handles both keying schemes:
     * <ul>
     *   <li><b>Classic bid</b> payment ({@code bid_id} set), and</li>
     *   <li><b>Negotiation / dedicated-trip</b> payment (keyed on {@code negotiation_thread_id},
     *       {@code bid_id} null — the bid is resolved via its linked thread).</li>
     * </ul>
     *
     * <p>Release mechanics mirror {@code DeliveryEventListener}:
     * <ul>
     *   <li><b>legacy destination charge</b> → capture the PaymentIntent (Stripe routes funds to
     *       the traveler via {@code transfer_data} set at creation);</li>
     *   <li><b>separate charges &amp; transfers</b> → capture the held PI if still
     *       {@code requires_capture}, then {@code Transfer} the net (amount − commission) to the
     *       traveler's Connect account. Capturing alone would only move the money to the platform
     *       balance — it would NOT pay the traveler.</li>
     * </ul>
     * Marks any related ESCROW_J48_TIMEOUT alert as resolved.
     */
    @PostMapping("/{id}/force-release")
    @Transactional
    public ResponseEntity<PaymentResponse> forceRelease(@PathVariable UUID id) {
        PaymentEntity payment = paymentRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found",
                        "Paiement introuvable"));

        // Resolve the bid (classic or negotiation-materialised) → announcement → traveler.
        // Negotiation payments carry a null bid_id, so the bid is found via its linked thread id.
        BidEntity bid = resolveBid(payment);

        // Safety guard: a CANCELLED trip's escrow must be REFUNDED to the sender (cancellation
        // flow), never transferred to the traveler. Refuse the force-release before any status
        // flip or Stripe transfer.
        if (bid != null && bid.getStatus() == BidStatus.CANCELLED) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "bid-cancelled",
                    "Bid Cancelled",
                    "Le colis est annulé — l'escrow doit être remboursé à l'expéditeur, pas transféré au voyageur");
        }

        AnnouncementEntity announcement = (bid != null)
                ? announcementRepository.findById(bid.getAnnouncementId()).orElse(null)
                : null;
        UUID travelerId = (announcement != null) ? announcement.getTravelerId() : null;
        UserEntity traveler = (travelerId != null)
                ? userRepository.findById(travelerId).orElse(null)
                : null;
        UUID bidId = (bid != null) ? bid.getId() : payment.getBidId();

        // Atomic ESCROW → RELEASED transition — prevents a double release/transfer race.
        int updated = paymentRepository.markReleasedIfEscrow(id, LocalDateTime.now(ZoneOffset.UTC));
        if (updated == 0) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-in-escrow",
                    "Invalid Status",
                    "Seuls les paiements en statut ESCROW peuvent faire l'objet d'une libération forcée");
        }

        try {
            if (payment.isLegacyDestinationCharge()) {
                // Destination-charge model: capturing routes funds to the traveler via transfer_data.
                PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
                if (STATUS_REQUIRES_CAPTURE.equals(pi.getStatus())) {
                    pi.capture();
                }
            } else {
                // Separate charges & transfers: the traveler must have a Connect account to receive
                // the payout. Fail (and roll back the RELEASED flip) rather than trap captured funds.
                if (traveler == null || traveler.getStripeAccountId() == null
                        || traveler.getStripeAccountId().isBlank()) {
                    throw new DonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "traveler-no-connect",
                            "Invalid Traveler",
                            "Voyageur introuvable ou sans compte Stripe Connect — transfert impossible");
                }

                PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
                // Ensure the funds are on the platform balance before transferring.
                if (STATUS_REQUIRES_CAPTURE.equals(pi.getStatus())) {
                    pi.capture();
                }
                String chargeId = (payment.getStripeChargeId() != null)
                        ? payment.getStripeChargeId()
                        : pi.getLatestCharge();

                BigDecimal net = payment.getAmount().subtract(payment.getCommissionAmount());
                long netCents = net.multiply(BigDecimal.valueOf(100)).longValueExact();

                TransferCreateParams.Builder builder = TransferCreateParams.builder()
                        .setAmount(netCents)
                        .setCurrency("eur")
                        .setDestination(traveler.getStripeAccountId())
                        .putMetadata("bid_id", bidId != null ? bidId.toString() : "")
                        .putMetadata("payment_id", id.toString())
                        .putMetadata("source", "admin-force-release");
                if (chargeId != null && !chargeId.isBlank()) {
                    builder.setSourceTransaction(chargeId);
                }
                Transfer.create(builder.build());
            }
        } catch (StripeException e) {
            log.error("Admin force-release: Stripe op failed for payment {} (PI={}): {}",
                    id, payment.getStripePaymentIntentId(), e.getMessage(), e);
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "stripe-release-failed",
                    "Stripe Error",
                    "Impossible de libérer le paiement Stripe. Veuillez réessayer.");
        }

        // Reflect the committed DB transition on the managed entity (the @Modifying CAS above
        // does not refresh it) so the response and any downstream flush are consistent.
        payment.setStatus(PaymentStatus.RELEASED);
        payment.setEscrowReleasedAt(LocalDateTime.now(ZoneOffset.UTC));

        // Resolve any open ESCROW_J48_TIMEOUT alerts for this payment
        resolveRelatedAlerts(id);

        auditService.log(
                "PAYMENT",
                payment.getId(),
                "ESCROW_FORCE_RELEASED",
                bidId,
                Map.of(
                        "paymentId", id.toString(),
                        "bidId", String.valueOf(bidId),
                        "piId", payment.getStripePaymentIntentId(),
                        "amount", payment.getAmount().toPlainString()
                )
        );

        // Notify the traveler of the payout (parity with DeliveryEventListener).
        if (bidId != null && travelerId != null) {
            eventPublisher.publishEvent(new PaymentReleasedEvent(
                    bidId, travelerId, bid.getSenderId(), payment.getAmount()));
        }

        log.info("Admin force-released escrow for payment {} (bid={}, PI={})",
                id, bidId, payment.getStripePaymentIntentId());

        return ResponseEntity.ok(toResponse(payment));
    }

    /**
     * POST /admin/payments/{id}/refund
     * Manually refunds the sender (Stripe {@code Refund}) for an escrowed payment — the
     * counterpart to force-release. Used when a paid trip is cancelled / parcel refused /
     * dispute resolved for the sender and the automatic refund did not run.
     * Only allowed when status is ESCROW.
     */
    @PostMapping("/{id}/refund")
    @Transactional
    public ResponseEntity<PaymentResponse> refund(@PathVariable UUID id) {
        PaymentEntity payment = paymentRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found",
                        "Paiement introuvable"));

        // Atomic ESCROW → REFUNDED transition — prevents a double refund race.
        int updated = paymentRepository.markRefundedIfEscrow(id);
        if (updated == 0) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-in-escrow",
                    "Invalid Status",
                    "Seuls les paiements en statut ESCROW peuvent être remboursés");
        }

        try {
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(payment.getStripePaymentIntentId())
                    .build());
        } catch (StripeException e) {
            log.error("Admin refund: Stripe refund failed for payment {} (PI={}): {}",
                    id, payment.getStripePaymentIntentId(), e.getMessage(), e);
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "stripe-refund-failed",
                    "Stripe Error",
                    "Impossible de rembourser le paiement Stripe. Veuillez réessayer.");
        }

        // Reflect the committed DB transition on the managed entity for the response.
        payment.setStatus(PaymentStatus.REFUNDED);

        resolveRelatedAlerts(id);

        auditService.log(
                "PAYMENT",
                payment.getId(),
                "ESCROW_FORCE_REFUNDED",
                payment.getBidId(),
                Map.of(
                        "paymentId", id.toString(),
                        "bidId", String.valueOf(payment.getBidId()),
                        "piId", payment.getStripePaymentIntentId(),
                        "amount", payment.getAmount().toPlainString()
                )
        );

        log.info("Admin refunded escrow for payment {} (PI={})", id, payment.getStripePaymentIntentId());

        return ResponseEntity.ok(toResponse(payment));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the bid behind a payment. Classic payments key on {@code bid_id};
     * negotiation/dedicated-trip payments key on {@code negotiation_thread_id} and the bid
     * is the one materialised from that thread.
     */
    private BidEntity resolveBid(PaymentEntity payment) {
        if (payment.getBidId() != null) {
            return bidRepository.findById(payment.getBidId()).orElse(null);
        }
        if (payment.getNegotiationThreadId() != null) {
            return bidRepository.findByLinkedNegotiationThreadId(payment.getNegotiationThreadId())
                    .orElse(null);
        }
        return null;
    }

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
