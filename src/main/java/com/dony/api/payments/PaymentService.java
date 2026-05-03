package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.payments.dto.ConnectAccountResponse;
import com.dony.api.payments.dto.CreatePaymentRequest;
import com.dony.api.payments.dto.OnboardingLinkResponse;
import com.dony.api.payments.dto.PaymentResponse;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import com.dony.api.payments.events.PaymentEscrowReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final String webhookSecret;

    @Value("${dony.stripe.return-url:https://dony.app/payments/onboarding/return}")
    private String returnUrl;

    @Value("${dony.stripe.refresh-url:https://dony.app/payments/onboarding/refresh}")
    private String refreshUrl;

    @Value("${dony.commission.rate:0.12}")
    private BigDecimal commissionRate;

    public PaymentService(UserRepository userRepository,
                          BidRepository bidRepository,
                          AnnouncementRepository announcementRepository,
                          PaymentRepository paymentRepository,
                          AuditService auditService,
                          ApplicationEventPublisher eventPublisher,
                          @Qualifier("stripeWebhookSecret") String webhookSecret) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.webhookSecret = webhookSecret;
    }

    // ── Story 6.2 : Onboarding Stripe Connect ────────────────────────────────

    public ConnectAccountResponse createConnectAccount(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);

        if (user.getStripeAccountId() != null) {
            return new ConnectAccountResponse(user.getStripeAccountId(), user.isStripeOnboarded());
        }

        try {
            AccountCreateParams params = AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS)
                    .setCountry("FR")
                    .setEmail(user.getEmail())
                    .setCapabilities(AccountCreateParams.Capabilities.builder()
                            .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                    .setRequested(true)
                                    .build())
                            .build())
                    .putMetadata("user_id", user.getId().toString())
                    .build();

            Account account = Account.create(params);
            user.setStripeAccountId(account.getId());
            userRepository.save(user);

            auditService.log("USER", user.getId(), "STRIPE_ACCOUNT_CREATED", user.getId(),
                    Map.of("stripeAccountId", account.getId()));

            log.info("Stripe Express account created for user {} : {}", user.getId(), account.getId());
            return new ConnectAccountResponse(account.getId(), false);

        } catch (Exception e) {
            log.error("Failed to create Stripe account for user {}", user.getId(), e);
            throw new DonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "stripe-account-creation-failed", "Stripe Error",
                    "Impossible de créer le compte de paiement");
        }
    }

    public OnboardingLinkResponse createOnboardingLink(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);

        if (user.getStripeAccountId() == null) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                    "stripe-account-required", "No Stripe Account",
                    "Créez d'abord un compte Stripe Connect");
        }

        try {
            AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                    .setAccount(user.getStripeAccountId())
                    .setReturnUrl(returnUrl)
                    .setRefreshUrl(refreshUrl)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build();

            AccountLink link = AccountLink.create(params);
            return new OnboardingLinkResponse(link.getUrl());

        } catch (Exception e) {
            log.error("Failed to create onboarding link for user {}", user.getId(), e);
            throw new DonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "stripe-link-creation-failed", "Stripe Error",
                    "Impossible de générer le lien d'onboarding");
        }
    }

    // ── Story 6.3 : Paiement expéditeur avec création d'escrow ───────────────

    public PaymentResponse createEscrow(CreatePaymentRequest request, String firebaseUid) {
        UserEntity sender = findUser(firebaseUid);
        UUID bidId = request.getBidId();

        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));

        if (!bid.getSenderId().equals(sender.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN,
                    "forbidden", "Forbidden", "Cette demande ne vous appartient pas");
        }

        // Le paiement est autorisé dès que le bid est PENDING ou ACCEPTED.
        // Si le voyageur refuse (REJECTED), le paiement sera annulé/remboursé automatiquement.
        if (bid.getStatus() == BidStatus.REJECTED
                || bid.getStatus() == BidStatus.CANCELLED
                || bid.getStatus() == BidStatus.COMPLETED) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "bid-not-payable", "Bid Not Payable",
                    "Cette demande ne peut plus être payée (statut : " + bid.getStatus() + ")");
        }

        // Idempotency: reuse existing non-failed payment
        Optional<PaymentEntity> existing = paymentRepository.findByBidId(bidId);
        if (existing.isPresent()) {
            PaymentEntity payment = existing.get();
            // Paiement déjà autorisé ou libéré → ne pas recréer
            if (payment.getStatus() == PaymentStatus.ESCROW
                    || payment.getStatus() == PaymentStatus.RELEASED) {
                throw new DonyBusinessException(HttpStatus.CONFLICT,
                        "payment-already-completed", "Payment Already Completed",
                        "Le paiement pour cette demande a déjà été effectué");
            }
            // PENDING → retourner le clientSecret existant pour que le client finalise
            if (payment.getStatus() == PaymentStatus.PENDING) {
                try {
                    PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
                    if ("requires_payment_method".equals(pi.getStatus())
                            || "requires_confirmation".equals(pi.getStatus())) {
                        return toPaymentResponse(payment, pi.getClientSecret());
                    }
                    // PI déjà autorisé côté Stripe mais pas encore mis à jour en DB → conflict
                    throw new DonyBusinessException(HttpStatus.CONFLICT,
                            "payment-already-completed", "Payment Already Completed",
                            "Le paiement pour cette demande a déjà été effectué");
                } catch (StripeException e) {
                    log.warn("Could not retrieve existing PaymentIntent for bid {}, creating new one", bidId);
                }
            }
            // FAILED → laisser passer pour créer un nouveau PaymentIntent
        }

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        UserEntity traveler = userRepository.findById(announcement.getTravelerId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "traveler-not-found", "Traveler Not Found", "Voyageur introuvable"));

        if (!traveler.isStripeOnboarded() || traveler.getStripeAccountId() == null || traveler.getStripeAccountId().isBlank()) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "traveler-not-onboarded", "Traveler Not Onboarded",
                    "Le voyageur n'a pas encore configuré son compte bancaire");
        }

        BigDecimal amount = bid.getWeightKg()
                .multiply(announcement.getPricePerKg())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        long amountCents = amount.multiply(BigDecimal.valueOf(100)).longValue();
        long commissionCents = commission.multiply(BigDecimal.valueOf(100)).longValue();

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency("eur")
                    .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                    .setApplicationFeeAmount(commissionCents)
                    .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                            .setDestination(traveler.getStripeAccountId())
                            .build())
                    .putMetadata("bid_id", bidId.toString())
                    .putMetadata("sender_id", sender.getId().toString())
                    .putMetadata("traveler_id", traveler.getId().toString())
                    .build();

            PaymentIntent pi = PaymentIntent.create(params);

            PaymentEntity payment = new PaymentEntity();
            payment.setBidId(bidId);
            payment.setStripePaymentIntentId(pi.getId());
            payment.setAmount(amount);
            payment.setCommissionAmount(commission);
            payment.setStatus(PaymentStatus.PENDING);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_ESCROW_CREATED", sender.getId(),
                    Map.of("bidId", bidId, "amount", amount, "commission", commission,
                            "piId", pi.getId()));

            log.info("PaymentIntent {} created for bid {} (sender={})", pi.getId(), bidId, sender.getId());
            return toPaymentResponse(payment, pi.getClientSecret());

        } catch (StripeException e) {
            log.error("Stripe PaymentIntent creation failed for bid {}", bidId, e);
            throw new DonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "payment-creation-failed", "Payment Error",
                    "Impossible de créer le paiement. Veuillez réessayer.");
        }
    }

    // ── Webhook Stripe ────────────────────────────────────────────────────────

    public void handleWebhook(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature");
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST,
                    "invalid-webhook-signature", "Webhook Error",
                    "Signature webhook invalide");
        }

        log.info("Stripe webhook received: {}", event.getType());

        switch (event.getType()) {
            case "account.updated" -> handleAccountUpdated(event);
            // payment_intent.amount_capturable_updated fires when card is authorized (capture_method=manual)
            case "payment_intent.amount_capturable_updated" -> handlePaymentEscrowActive(event);
            case "payment_intent.payment_failed" -> handlePaymentFailed(event);
            // Story 6.7 — confirm refund initiated by TripCancelledEventListener
            case "charge.refunded" -> handleChargeRefunded(event);
            default -> log.debug("Unhandled webhook event: {}", event.getType());
        }
    }

    private void handleAccountUpdated(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            Account account = (Account) obj;
            if (Boolean.TRUE.equals(account.getChargesEnabled())) {
                userRepository.findByStripeAccountId(account.getId()).ifPresent(user -> {
                    if (!user.isStripeOnboarded()) {
                        user.setStripeOnboarded(true);
                        userRepository.save(user);
                        auditService.log("USER", user.getId(), "STRIPE_ONBOARDING_COMPLETE",
                                user.getId(), Map.of("stripeAccountId", account.getId()));
                        log.info("Stripe onboarding complete for user {}", user.getId());
                    }
                });
            }
        });
    }

    private void handlePaymentEscrowActive(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            PaymentIntent pi = (PaymentIntent) obj;
            paymentRepository.findByStripePaymentIntentId(pi.getId()).ifPresent(payment -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.ESCROW);
                    paymentRepository.save(payment);
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_ESCROW_ACTIVE",
                            payment.getBidId(),
                            Map.of("piId", pi.getId(), "amountCapturable", pi.getAmountCapturable()));
                    log.info("Payment {} now in ESCROW (PI={})", payment.getId(), pi.getId());
                    eventPublisher.publishEvent(new PaymentEscrowReadyEvent(payment.getBidId(), payment.getId()));
                }
            });
        });
    }

    private void handlePaymentFailed(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            PaymentIntent pi = (PaymentIntent) obj;
            paymentRepository.findByStripePaymentIntentId(pi.getId()).ifPresent(payment -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.FAILED);
                    paymentRepository.save(payment);
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_FAILED",
                            payment.getBidId(),
                            Map.of("piId", pi.getId()));
                    log.warn("Payment {} FAILED (PI={})", payment.getId(), pi.getId());
                }
            });
        });
    }

    private void handleChargeRefunded(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            Charge charge = (Charge) obj;
            String piId = charge.getPaymentIntent();
            if (piId == null) {
                log.debug("charge.refunded event has no paymentIntent — ignoring");
                return;
            }
            paymentRepository.findByStripePaymentIntentId(piId).ifPresent(payment -> {
                if (payment.getStatus() == PaymentStatus.REFUNDED) {
                    // Already marked REFUNDED by TripCancelledEventListener — idempotent, skip
                    log.info("Refund confirmed by Stripe webhook for payment {} (already REFUNDED)", payment.getId());
                    return;
                }
                log.info("Refund confirmed by Stripe webhook for payment {}", payment.getId());
                // If for some reason the listener missed it, mark it now
                payment.setStatus(PaymentStatus.REFUNDED);
                paymentRepository.save(payment);
                auditService.log("PAYMENT", payment.getId(), "PAYMENT_REFUNDED",
                        payment.getBidId(),
                        Map.of("piId", piId, "source", "stripe_webhook"));
            });
        });
    }

    // ── Story 6.3 : Statut paiement pour un bid ──────────────────────────────

    public Optional<PaymentResponse> getPaymentStatusForBid(UUID bidId) {
        return paymentRepository.findByBidId(bidId)
                .map(payment -> toPaymentResponse(payment, null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserEntity findUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private PaymentResponse toPaymentResponse(PaymentEntity payment, String clientSecret) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBidId(),
                clientSecret,
                payment.getAmount(),
                payment.getCommissionAmount(),
                payment.getStatus().name()
        );
    }

    /**
     * Annule un PaymentIntent en mode pré-autorisation.
     * No-op si paymentIntentId est null/blank.
     * Throws StripeException pour permettre au scheduler de détecter la race condition (PI déjà succeeded).
     */
    public void cancelPaymentIntent(String paymentIntentId) throws StripeException {
        if (paymentIntentId == null || paymentIntentId.isBlank()) return;
        PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
        pi.cancel();
        log.info("PaymentIntent {} cancelled", paymentIntentId);
    }

    /**
     * Capture un PaymentIntent pré-autorisé (manual capture).
     * No-op si paymentIntentId est null/blank.
     */
    public void capturePaymentIntent(String paymentIntentId) throws StripeException {
        if (paymentIntentId == null || paymentIntentId.isBlank()) return;
        PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
        pi.capture();
        log.info("PaymentIntent {} captured", paymentIntentId);
    }
}
