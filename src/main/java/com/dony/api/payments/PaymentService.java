package com.dony.api.payments;

import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.ProcessedStripeEvent;
import com.dony.api.common.ProcessedStripeEventRepository;
import com.dony.api.config.StripeConnectProperties;
import com.dony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.payments.dto.ConnectAccountResponse;
import com.dony.api.payments.dto.CreatePaymentRequest;
import com.dony.api.payments.dto.OnboardingLinkResponse;
import com.dony.api.payments.dto.PaymentResponse;
import com.dony.api.payments.events.StripeOnboardingCompletedEvent;
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
import com.stripe.param.AccountUpdateParams;
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
    private final StripeConnectProperties stripeConnectProperties;
    private final ProcessedStripeEventRepository processedStripeEventRepository;

    @Value("${dony.commission.rate:0.12}")
    private BigDecimal commissionRate;

    public PaymentService(UserRepository userRepository,
                          BidRepository bidRepository,
                          AnnouncementRepository announcementRepository,
                          PaymentRepository paymentRepository,
                          AuditService auditService,
                          ApplicationEventPublisher eventPublisher,
                          @Qualifier("stripeWebhookSecret") String webhookSecret,
                          StripeConnectProperties stripeConnectProperties,
                          ProcessedStripeEventRepository processedStripeEventRepository) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.webhookSecret = webhookSecret;
        this.stripeConnectProperties = stripeConnectProperties;
        this.processedStripeEventRepository = processedStripeEventRepository;
    }

    // ── Story 6.2 : Onboarding Stripe Connect ────────────────────────────────

    public ConnectAccountResponse createConnectAccount(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);

        // Lock the user row to prevent concurrent Stripe account creation (race condition guard)
        user = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));

        // Re-check after acquiring lock — another thread may have created the account already.
        // Also verify the existing account still exists in Stripe (it can be manually deleted in
        // sandbox or expire). If it's missing, reset and recreate.
        if (user.getStripeAccountId() != null) {
            try {
                Account.retrieve(user.getStripeAccountId());
                return new ConnectAccountResponse(user.getStripeAccountId(), user.getStripeAccountStatus());
            } catch (StripeException e) {
                if (!isStripeAccountMissing(e)) {
                    log.error("Failed to verify Stripe account {} for user {}",
                            user.getStripeAccountId(), user.getId(), e);
                    throw new DonyBusinessException(HttpStatus.BAD_GATEWAY,
                            "stripe-account-verification-failed", "Stripe Error",
                            "Impossible de vérifier le compte de paiement");
                }
                log.warn("Stripe account {} no longer exists for user {} — resetting and recreating",
                        user.getStripeAccountId(), user.getId());
                resetStripeAccountState(user);
                // fall through to creation below
            }
        }

        try {
            AccountCreateParams params = AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS)
                    .setCountry(user.getCountry())
                    .setEmail(user.getEmail())
                    .setBusinessType(
                            user.isProAccount()
                                    ? AccountCreateParams.BusinessType.COMPANY
                                    : AccountCreateParams.BusinessType.INDIVIDUAL
                    )
                    .setCapabilities(
                            AccountCreateParams.Capabilities.builder()
                                    .setCardPayments(
                                            AccountCreateParams.Capabilities.CardPayments.builder()
                                                    .setRequested(true) // CRITICAL: required for on_behalf_of
                                                    .build()
                                    )
                                    .setTransfers(
                                            AccountCreateParams.Capabilities.Transfers.builder()
                                                    .setRequested(true)
                                                    .build()
                                    )
                                    .build()
                    )
                    .setBusinessProfile(
                            AccountCreateParams.BusinessProfile.builder()
                                    // TODO: validate MCC 4215 vs 4214 in Stripe sandbox for Express FR individual accounts before prod
                                    .setMcc(stripeConnectProperties.mcc())
                                    .setProductDescription(stripeConnectProperties.productDescription())
                                    .setUrl(stripeConnectProperties.businessUrl())
                                    .build()
                    )
                    .setSettings(
                            AccountCreateParams.Settings.builder()
                                    .setPayouts(
                                            AccountCreateParams.Settings.Payouts.builder()
                                                    .setSchedule(
                                                            AccountCreateParams.Settings.Payouts.Schedule.builder()
                                                                    .setInterval(AccountCreateParams.Settings.Payouts.Schedule.Interval.DAILY)
                                                                    .build()
                                                    )
                                                    .build()
                                    )
                                    .build()
                    )
                    .putMetadata("user_id", user.getId().toString())
                    .build();

            Account account = Account.create(params);
            user.setStripeAccountId(account.getId());
            user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
            user.setStripeAccountCreatedAt(java.time.Instant.now());
            try {
                userRepository.save(user);
            } catch (Exception saveEx) {
                log.error("Failed to save user after Stripe account creation. orphan_account_id={}, user_id={}",
                        account.getId(), user.getId(), saveEx);
                throw saveEx;
            }

            auditService.log("USER", user.getId(), "STRIPE_ACCOUNT_CREATED", user.getId(),
                    Map.of("stripeAccountId", account.getId()));

            log.info("Stripe Express account created for user {} : {}", user.getId(), account.getId());
            return new ConnectAccountResponse(account.getId(), StripeAccountStatus.PENDING_ONBOARDING);

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
                    .setReturnUrl(stripeConnectProperties.returnUrl())
                    .setRefreshUrl(stripeConnectProperties.refreshUrl())
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build();

            AccountLink link = AccountLink.create(params);
            return new OnboardingLinkResponse(link.getUrl());

        } catch (StripeException e) {
            if (isStripeAccountMissing(e)) {
                log.warn("Stripe account {} no longer exists for user {} — resetting state so user can recreate",
                        user.getStripeAccountId(), user.getId());
                resetStripeAccountState(user);
                userRepository.save(user);
                throw new DonyBusinessException(HttpStatus.CONFLICT,
                        "stripe-account-invalid", "Stripe Account Invalid",
                        "Votre compte de paiement n'est plus valide. Réessayez pour en créer un nouveau.");
            }
            log.error("Failed to create onboarding link for user {}", user.getId(), e);
            throw new DonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "stripe-link-creation-failed", "Stripe Error",
                    "Impossible de générer le lien d'onboarding");
        } catch (Exception e) {
            log.error("Failed to create onboarding link for user {}", user.getId(), e);
            throw new DonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "stripe-link-creation-failed", "Stripe Error",
                    "Impossible de générer le lien d'onboarding");
        }
    }

    /**
     * Returns true if the Stripe exception is a "resource_missing" error, typically thrown
     * when an account, payment intent, or other resource ID stored in our DB no longer exists
     * in Stripe (account deleted in sandbox, key rotation, expired test data, etc.).
     */
    private static boolean isStripeAccountMissing(StripeException e) {
        return "resource_missing".equals(e.getCode());
    }

    /**
     * Resets the user's Stripe Connect state in memory. Caller is responsible for persisting
     * the change via {@code userRepository.save(user)} when this is not part of the same
     * transactional flow (e.g. fall-through to immediate recreation does not require save).
     */
    private void resetStripeAccountState(UserEntity user) {
        user.setStripeAccountId(null);
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        user.setStripeAccountCreatedAt(null);
        user.setStripeOnboardingCompletedAt(null);
    }

    /**
     * Pulls the latest state of the user's Stripe Connect account from Stripe and syncs the
     * local {@code stripe_onboarded} flag. Useful when the {@code account.updated} webhook
     * was missed (e.g. local dev without Stripe CLI, or transient network issue in prod).
     *
     * <p>Authoritative source = Stripe ({@code account.charges_enabled}). We mirror it.
     */
    public ConnectAccountResponse refreshConnectAccount(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);

        if (user.getStripeAccountId() == null || user.getStripeAccountId().isBlank()) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                    "stripe-account-required", "No Stripe Account",
                    "Aucun compte Stripe à rafraîchir — créez-en un d'abord");
        }

        try {
            Account account = Account.retrieve(user.getStripeAccountId());
            boolean chargesEnabled = Boolean.TRUE.equals(account.getChargesEnabled());
            StripeAccountStatus newStatus = deriveStripeAccountStatus(account);

            if (newStatus != user.getStripeAccountStatus()) {
                user.setStripeAccountStatus(newStatus);
                if (newStatus == StripeAccountStatus.ONBOARDING_COMPLETE
                        && user.getStripeOnboardingCompletedAt() == null) {
                    user.setStripeOnboardingCompletedAt(java.time.Instant.now());
                }
                userRepository.save(user);
                String action = chargesEnabled ? "STRIPE_ONBOARDING_COMPLETE" : "STRIPE_ONBOARDING_REVOKED";
                auditService.log("USER", user.getId(), action, user.getId(),
                        Map.of("stripeAccountId", account.getId(),
                                "source", "manual-refresh"));
                log.info("Stripe onboarding state synced for user {} : newStatus={}",
                        user.getId(), newStatus);
            }

            return new ConnectAccountResponse(account.getId(), user.getStripeAccountStatus());

        } catch (StripeException e) {
            log.error("Failed to refresh Stripe account {} for user {}",
                    user.getStripeAccountId(), user.getId(), e);
            throw new DonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "stripe-refresh-failed", "Stripe Error",
                    "Impossible de récupérer l'état du compte Stripe");
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

        if (traveler.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE
                || traveler.getStripeAccountId() == null
                || traveler.getStripeAccountId().isBlank()) {
            throw new TravelerNotEligibleForPaymentException(traveler.getId());
        }

        BigDecimal amount = bid.getWeightKg()
                .multiply(announcement.getPricePerKg())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        long amountCents = amount.multiply(BigDecimal.valueOf(100)).longValue();

        try {
            // Compatibilité comptes legacy : avant cette fix, certains comptes Stripe Connect
            // ont été créés sans la capacité card_payments (seulement transfers).
            // Stripe rejette PaymentIntent.create(on_behalf_of=…) si card_payments n'est pas active.
            // On la demande de manière idempotente : si déjà active, no-op.
            ensureCardPaymentsCapability(traveler.getStripeAccountId());

            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency("eur")
                    .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                    .setOnBehalfOf(traveler.getStripeAccountId())
                    .setStatementDescriptorSuffix("DONY")
                    // No application_fee_amount, no transfer_data: separate charges and transfers model.
                    // Funds stay on platform balance until DeliveryEventListener triggers Transfer.create
                    // at delivery confirmation. Commission is held back implicitly (transfer = total - commission).
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
            payment.setLegacyDestinationCharge(false);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_ESCROW_CREATED", sender.getId(),
                    Map.of("bidId", bidId, "amount", amount, "commission", commission,
                            "piId", pi.getId()));

            log.info("PaymentIntent {} created for bid {} (sender={})", pi.getId(), bidId, sender.getId());
            return toPaymentResponse(payment, pi.getClientSecret());

        } catch (StripeException e) {
            if (isStripeAccountMissing(e)) {
                log.warn("Traveler {} stripe account {} no longer exists in Stripe — resetting state",
                        traveler.getId(), traveler.getStripeAccountId(), e);
                resetStripeAccountState(traveler);
                userRepository.save(traveler);
                throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "traveler-stripe-invalid", "Traveler Stripe Account Invalid",
                        "Le voyageur doit reconfigurer son compte de paiement avant de pouvoir recevoir cette demande.");
            }
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

        // Idempotency: skip already-processed events
        if (processedStripeEventRepository.existsByEventId(event.getId())) {
            log.info("Stripe payment event {} already processed — skipping", event.getId());
            return;
        }
        processedStripeEventRepository.save(new ProcessedStripeEvent(event.getId()));

        dispatchWebhookEvent(event);
    }

    /**
     * Dispatches a verified Stripe Event to the appropriate handler.
     * Package-private to allow tests to invoke without constructing a signed payload.
     */
    void dispatchWebhookEvent(Event event) {
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
        event.getDataObjectDeserializer().getObject().ifPresentOrElse(
            obj -> {
                Account account = (Account) obj;
                String accountId = account.getId();

                userRepository.findByStripeAccountId(accountId).ifPresent(user -> {
                    StripeAccountStatus newStatus = deriveStripeAccountStatus(account);

                    if (newStatus == StripeAccountStatus.PENDING_ONBOARDING) {
                        return; // still pending, no state change
                    }

                    // Only emit event on first transition to ONBOARDING_COMPLETE
                    if (newStatus == StripeAccountStatus.ONBOARDING_COMPLETE
                            && user.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE) {
                        user.setStripeOnboardingCompletedAt(java.time.Instant.now());
                        eventPublisher.publishEvent(new StripeOnboardingCompletedEvent(user.getId()));
                        auditService.log("USER", user.getId(), "STRIPE_ONBOARDING_COMPLETE",
                                user.getId(), Map.of("stripeAccountId", accountId));
                        log.info("Stripe onboarding complete for user {}", user.getId());
                    }

                    user.setStripeAccountStatus(newStatus);
                    userRepository.save(user);
                });
            },
            () -> log.warn("handleAccountUpdated: could not deserialize account object for event {}", event.getId())
        );
    }

    /**
     * Derives the {@link StripeAccountStatus} from a Stripe {@link Account} object.
     * Single source of truth used by both {@code handleAccountUpdated} and {@code refreshConnectAccount}.
     */
    private StripeAccountStatus deriveStripeAccountStatus(Account account) {
        boolean chargesEnabled = Boolean.TRUE.equals(account.getChargesEnabled());
        boolean payoutsEnabled = Boolean.TRUE.equals(account.getPayoutsEnabled());
        String disabledReason = account.getRequirements() != null
                ? account.getRequirements().getDisabledReason()
                : null;

        if (chargesEnabled && payoutsEnabled) {
            return StripeAccountStatus.ONBOARDING_COMPLETE;
        } else if (disabledReason != null && disabledReason.startsWith("rejected")) {
            return StripeAccountStatus.REJECTED;
        } else if (disabledReason != null) {
            return StripeAccountStatus.DISABLED;
        } else {
            return StripeAccountStatus.PENDING_ONBOARDING;
        }
    }

    private void handlePaymentEscrowActive(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            PaymentIntent pi = (PaymentIntent) obj;
            paymentRepository.findByStripePaymentIntentId(pi.getId()).ifPresent(payment -> {
                boolean changed = false;

                // Persist the Stripe Charge id for later Transfer.sourceTransaction reconciliation
                // (Task 9b). Idempotent: only set once.
                String chargeId = pi.getLatestCharge();
                if (chargeId != null && payment.getStripeChargeId() == null) {
                    payment.setStripeChargeId(chargeId);
                    changed = true;
                }

                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.ESCROW);
                    changed = true;
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_ESCROW_ACTIVE",
                            payment.getBidId(),
                            Map.of("piId", pi.getId(), "amountCapturable", pi.getAmountCapturable()));
                    log.info("Payment {} now in ESCROW (PI={})", payment.getId(), pi.getId());
                    eventPublisher.publishEvent(new PaymentEscrowReadyEvent(payment.getBidId(), payment.getId()));
                }

                if (changed) {
                    paymentRepository.save(payment);
                }
            });
            // Promote the AWAITING_PAYMENT bid to PENDING (independent of Payment row state)
            promoteBidOnPaymentAuthorized(pi.getId());

            // Marketplace package_request flow: if this PI is bound to a negotiation thread,
            // ask the NegotiationService to finalize via the application context (avoid circular DI).
            String scope = pi.getMetadata().get("scope");
            String negotiationThreadId = pi.getMetadata().get("negotiation_thread_id");
            if ("NEGOTIATION".equals(scope) && negotiationThreadId != null) {
                eventPublisher.publishEvent(new NegotiationPaymentAuthorizedEvent(
                        java.util.UUID.fromString(negotiationThreadId),
                        java.util.UUID.fromString(pi.getMetadata().get("sender_id")),
                        pi.getId()
                ));
            }
        });
    }

    /**
     * Promotes a bid from AWAITING_PAYMENT → PENDING when the Stripe PaymentIntent
     * has been authorized (capture_method=manual hold posted).
     * Publishes BidCreatedEvent so the traveler is notified.
     * Idempotent: silent no-op if bid not in AWAITING_PAYMENT.
     */
    @Transactional
    public void promoteBidOnPaymentAuthorized(String paymentIntentId) {
        bidRepository.findByPaymentIntentId(paymentIntentId).ifPresent(bid -> {
            if (bid.getStatus() != BidStatus.AWAITING_PAYMENT) return;

            bid.setStatus(BidStatus.PENDING);
            bid.setAwaitingPaymentExpiresAt(null);
            bidRepository.save(bid);

            AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                    .orElseThrow(() -> new IllegalStateException("announcement not found for bid " + bid.getId()));
            UserEntity sender = userRepository.findById(bid.getSenderId()).orElse(null);
            String senderName = (sender != null && sender.getFirstName() != null && !sender.getFirstName().isBlank())
                    ? sender.getFirstName() : "Un expéditeur";
            String corridor = announcement.getDepartureCity() + " → " + announcement.getArrivalCity();

            auditService.log("BID", bid.getId(), "BID_CREATED", bid.getSenderId(),
                    Map.of("announcementId", bid.getAnnouncementId().toString(),
                            "weightKg", bid.getWeightKg().toString(),
                            "paymentIntentId", paymentIntentId));

            eventPublisher.publishEvent(new BidCreatedEvent(
                    bid.getId(), announcement.getId(), announcement.getTravelerId(), bid.getSenderId(),
                    senderName, bid.getWeightKg(), corridor));

            log.info("Bid {} promoted to PENDING (PI={})", bid.getId(), paymentIntentId);
        });
    }

    /**
     * Synchronous safety net for clients that just confirmed payment via Stripe SDK.
     * Retrieves the PaymentIntent from Stripe and, if authorized
     * (requires_capture / succeeded / processing), promotes the bid to PENDING.
     *
     * This avoids depending on the Stripe webhook in environments where it
     * cannot reach the backend (local dev) and acts as a redundant safety net
     * in production. Idempotent: no-op if the bid is not in AWAITING_PAYMENT.
     *
     * @return true if the bid is now in PENDING state, false otherwise
     */
    @Transactional
    public boolean confirmBidPayment(UUID bidId) {
        BidEntity bid = bidRepository.findById(bidId).orElseThrow(() ->
                new DonyBusinessException(HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Bid introuvable"));

        if (bid.getStatus() == BidStatus.PENDING) {
            return true;
        }
        if (bid.getStatus() != BidStatus.AWAITING_PAYMENT) {
            return false;
        }

        String piId = bid.getPaymentIntentId();
        if (piId == null) {
            log.warn("Bid {} in AWAITING_PAYMENT has no paymentIntentId", bidId);
            return false;
        }

        try {
            PaymentIntent pi = PaymentIntent.retrieve(piId);
            String status = pi.getStatus();
            if ("requires_capture".equals(status)
                    || "succeeded".equals(status)
                    || "processing".equals(status)) {
                promoteBidOnPaymentAuthorized(piId);
                return true;
            }
            log.info("Bid {} not promoted: PI {} status={}", bidId, piId, status);
            return false;
        } catch (StripeException e) {
            log.error("Failed to retrieve PI {} for bid {}: {}", piId, bidId, e.getMessage());
            throw new DonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-error", "Stripe Error",
                    "Impossible de vérifier le paiement auprès de Stripe");
        }
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

    public Optional<PaymentResponse> getPaymentStatusForBid(UUID bidId, String callerFirebaseUid) {
        UserEntity caller = findUser(callerFirebaseUid);

        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null) {
            return Optional.empty();
        }

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);

        boolean isSender = bid.getSenderId().equals(caller.getId());
        boolean isTraveler = announcement != null && announcement.getTravelerId().equals(caller.getId());

        if (!isSender && !isTraveler) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "access-denied",
                    "Access Denied", "Vous n'êtes pas autorisé à accéder à ce paiement");
        }

        return paymentRepository.findByBidId(bidId)
                .map(payment -> toPaymentResponse(payment, null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserEntity findUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    /**
     * Garantit que la capacité {@code card_payments} est demandée sur le compte Connect.
     * Sans elle, Stripe rejette {@code PaymentIntent.create(on_behalf_of=…)} :
     * « You cannot create a payment with on_behalf_of set to a connected account
     *   representing the transfers feature without enabling the card_payments feature. »
     * <p>
     * Idempotent : si la capacité est déjà active ou pending, no-op.
     * Si le compte n'a pas encore complété l'onboarding pour activer la capacité,
     * Stripe lèvera tout de même une erreur sur PaymentIntent.create — ce check ne masque pas
     * le besoin pour le voyageur de finaliser son onboarding.
     */
    private void ensureCardPaymentsCapability(String stripeAccountId) throws StripeException {
        Account account = Account.retrieve(stripeAccountId);
        String currentState = account.getCapabilities() == null
                ? null
                : account.getCapabilities().getCardPayments();
        if ("active".equals(currentState)) {
            return;
        }
        // pending → la capacité est demandée mais Stripe attend encore des infos
        // (typiquement un document KYC). On lève une erreur métier claire plutôt
        // que de laisser Stripe rejeter le PaymentIntent avec un message technique.
        if ("pending".equals(currentState)) {
            log.warn("card_payments pending on Stripe account {} — traveler needs to complete onboarding (KYC docs)",
                    stripeAccountId);
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "traveler-onboarding-pending", "Traveler Onboarding Pending",
                    "Le voyageur doit finaliser son inscription Stripe (pièce d'identité requise) "
                    + "avant de pouvoir recevoir des paiements.");
        }
        // null / inactive / unrequested → demander la capacité (idempotent côté Stripe)
        log.info("Requesting card_payments capability on legacy Stripe account {} (current={})",
                stripeAccountId, currentState);
        AccountUpdateParams updateParams = AccountUpdateParams.builder()
                .setCapabilities(
                        AccountUpdateParams.Capabilities.builder()
                                .setCardPayments(
                                        AccountUpdateParams.Capabilities.CardPayments.builder()
                                                .setRequested(true)
                                                .build()
                                )
                                .setTransfers(
                                        AccountUpdateParams.Capabilities.Transfers.builder()
                                                .setRequested(true)
                                                .build()
                                )
                                .build()
                )
                .build();
        Account updated = account.update(updateParams);
        String newState = updated.getCapabilities() == null
                ? null
                : updated.getCapabilities().getCardPayments();
        if (!"active".equals(newState)) {
            log.warn("card_payments still {} after update on account {} — traveler needs to complete onboarding",
                    newState, stripeAccountId);
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "traveler-onboarding-pending", "Traveler Onboarding Pending",
                    "Le voyageur doit finaliser son inscription Stripe (pièce d'identité requise) "
                    + "avant de pouvoir recevoir des paiements.");
        }
    }

    private PaymentResponse toPaymentResponse(PaymentEntity payment, String clientSecret) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBidId(),
                clientSecret,
                payment.getAmount(),
                payment.getCommissionAmount(),
                payment.getStatus().name(),
                payment.getStripePaymentIntentId()
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

    // ─── Package-request marketplace : escrow on negotiation thread ──────────────

    /**
     * Creates a Stripe escrow PaymentIntent for an AWAITING_PAYMENT negotiation thread.
     * Mirrors {@link #createEscrow} but bound to a negotiation_thread_id instead of bid_id.
     *
     * The traveler must have a fully-onboarded Stripe Connect account
     * (same eligibility rules as for bids).
     *
     * Returns clientSecret that the Flutter side passes to Stripe SDK confirmPayment.
     * On webhook payment_intent.amount_capturable_updated, the thread is finalized as ACCEPTED
     * via {@code com.dony.api.requests.service.NegotiationService.finalizeAfterPayment}.
     */
    public PaymentResponse createNegotiationEscrow(
            UUID threadId,
            UUID senderId,
            UUID travelerId,
            BigDecimal amountEur) {
        log.info("createNegotiationEscrow(threadId={}, senderId={}, travelerId={}, amount={})",
                threadId, senderId, travelerId, amountEur);

        UserEntity sender = userRepository.findById(senderId)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Sender introuvable"));
        UserEntity traveler = userRepository.findById(travelerId)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "traveler-not-found", "Traveler Not Found", "Voyageur introuvable"));

        // Idempotency: reuse existing non-failed payment for this thread
        Optional<PaymentEntity> existing = paymentRepository.findByNegotiationThreadId(threadId);
        if (existing.isPresent()) {
            PaymentEntity payment = existing.get();
            if (payment.getStatus() == PaymentStatus.ESCROW
                    || payment.getStatus() == PaymentStatus.RELEASED) {
                throw new DonyBusinessException(HttpStatus.CONFLICT,
                        "payment-already-completed", "Payment Already Completed",
                        "Le paiement pour cette négociation a déjà été effectué");
            }
            if (payment.getStatus() == PaymentStatus.PENDING) {
                try {
                    PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
                    if ("requires_payment_method".equals(pi.getStatus())
                            || "requires_confirmation".equals(pi.getStatus())) {
                        return toPaymentResponse(payment, pi.getClientSecret());
                    }
                    throw new DonyBusinessException(HttpStatus.CONFLICT,
                            "payment-already-completed", "Payment Already Completed",
                            "Le paiement pour cette négociation a déjà été effectué");
                } catch (StripeException e) {
                    log.warn("Could not retrieve existing PaymentIntent for thread {}, creating new one",
                            threadId);
                }
            }
        }

        if (traveler.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE
                || traveler.getStripeAccountId() == null
                || traveler.getStripeAccountId().isBlank()) {
            throw new TravelerNotEligibleForPaymentException(traveler.getId());
        }

        BigDecimal amount = amountEur.setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        long amountCents = amount.multiply(BigDecimal.valueOf(100)).longValue();

        try {
            ensureCardPaymentsCapability(traveler.getStripeAccountId());

            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency("eur")
                    .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                    .setOnBehalfOf(traveler.getStripeAccountId())
                    .setStatementDescriptorSuffix("DONY")
                    .putMetadata("negotiation_thread_id", threadId.toString())
                    .putMetadata("sender_id", sender.getId().toString())
                    .putMetadata("traveler_id", traveler.getId().toString())
                    .putMetadata("scope", "NEGOTIATION")
                    .build();

            PaymentIntent pi = PaymentIntent.create(params);

            PaymentEntity payment = new PaymentEntity();
            payment.setNegotiationThreadId(threadId);
            payment.setStripePaymentIntentId(pi.getId());
            payment.setAmount(amount);
            payment.setCommissionAmount(commission);
            payment.setStatus(PaymentStatus.PENDING);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "NEGOTIATION_ESCROW_CREATED",
                    sender.getId(),
                    java.util.Map.of("threadId", threadId.toString(),
                            "amount", amount.toString(),
                            "stripePaymentIntentId", pi.getId()));

            log.info("PaymentIntent {} created for negotiation thread {} (sender={})",
                    pi.getId(), threadId, sender.getId());
            return toPaymentResponse(payment, pi.getClientSecret());
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent creation failed for negotiation thread {}", threadId, e);
            throw new DonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "stripe-error", "Stripe Error",
                    "Échec création PaymentIntent : " + e.getMessage());
        }
    }
}
