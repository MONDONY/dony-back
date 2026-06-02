package com.dony.api.payments.cash;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.payments.cash.dto.AcceptBidResponse;
import com.dony.api.payments.cash.dto.AcceptanceStatusDto;
import com.dony.api.payments.cash.dto.CommissionMethodResponse;
import com.dony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.dony.api.payments.cash.dto.SetupCommissionMethodResponse;
import com.dony.api.payments.cash.event.CommissionMethodDetachedEvent;
import com.dony.api.payments.cash.exception.CommissionChargeFailedException;
import com.dony.api.payments.cash.exception.CommissionMethodMissingException;
import com.dony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException;
import com.dony.api.payments.wallet.InsufficientWalletBalanceException;
import com.dony.api.payments.wallet.WalletService;
import com.dony.api.payments.wallet.WalletTransactionRepository;
import com.dony.api.payments.wallet.WalletTransactionType;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CashCommissionService {

    private static final Logger log = LoggerFactory.getLogger(CashCommissionService.class);
    private static final String TRACKING_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom SECURE_RNG = new SecureRandom();

    private final CommissionProperties props;
    private final UserRepository userRepo;
    private final BidRepository bidRepo;
    private final AnnouncementRepository announcementRepo;
    private final ApplicationEventPublisher events;
    private final WalletService walletService;
    private final WalletTransactionRepository walletTransactionRepository;
    private final AuditService auditService;
    private Clock clock = Clock.systemUTC();

    public CashCommissionService(CommissionProperties props,
                                 UserRepository userRepo,
                                 BidRepository bidRepo,
                                 AnnouncementRepository announcementRepo,
                                 ApplicationEventPublisher events,
                                 WalletService walletService,
                                 WalletTransactionRepository walletTransactionRepository,
                                 AuditService auditService) {
        this.props = props;
        this.userRepo = userRepo;
        this.bidRepo = bidRepo;
        this.announcementRepo = announcementRepo;
        this.events = events;
        this.walletService = walletService;
        this.walletTransactionRepository = walletTransactionRepository;
        this.auditService = auditService;
    }

    /** Visible for testing — injects a fixed clock. */
    void setClock(Clock clock) { this.clock = clock; }

    // --- Commission calculation ---

    public BigDecimal computeCommission(BigDecimal declaredValue) {
        BigDecimal pct = declaredValue.multiply(props.rate()).setScale(2, RoundingMode.HALF_UP);
        return pct.compareTo(props.minimumAmount()) < 0 ? props.minimumAmount() : pct;
    }

    /** Calcule la commission pour un bid à partir de son annonce (weightKg × pricePerKg). */
    public BigDecimal computeBidCommission(BidEntity bid, AnnouncementEntity announcement) {
        BigDecimal cashAmount = bid.getWeightKg().multiply(announcement.getPricePerKg());
        return computeCommission(cashAmount);
    }

    // --- Card registration ---

    @Transactional
    public void saveCommissionMethod(UUID userId, String paymentMethodOrSetupIntentId) {
        UserEntity user = userRepo.findById(userId).orElseThrow();
        try {
            String paymentMethodId;
            if (paymentMethodOrSetupIntentId.startsWith("seti_")) {
                SetupIntent si = SetupIntent.retrieve(paymentMethodOrSetupIntentId);
                paymentMethodId = si.getPaymentMethod();
                if (paymentMethodId == null) {
                    throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "setup-not-confirmed", "Setup Not Confirmed",
                            "Le setup intent n'a pas encore de méthode de paiement attachée");
                }
            } else {
                paymentMethodId = paymentMethodOrSetupIntentId;
            }
            PaymentMethod pm = PaymentMethod.retrieve(paymentMethodId);
            if (pm.getCard() == null) {
                throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-payment-method", "Invalid Payment Method",
                        "La méthode de paiement fournie n'est pas une carte");
            }
            user.setCommissionPaymentMethodId(paymentMethodId);
            user.setCommissionCardBrand(pm.getCard().getBrand());
            user.setCommissionCardLast4(pm.getCard().getLast4());
            user.setCommissionCardExpMonth(pm.getCard().getExpMonth().intValue());
            user.setCommissionCardExpYear(pm.getCard().getExpYear().intValue());
            userRepo.save(user);
        } catch (StripeException e) {
            throw new RuntimeException("Impossible de récupérer les données Stripe", e);
        }
    }

    @Transactional
    public SetupCommissionMethodResponse setupCommissionMethod(UUID userId) {
        UserEntity user = userRepo.findById(userId).orElseThrow();
        ensureStripeCustomer(user);
        try {
            SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                    .setCustomer(user.getStripeCustomerId())
                    .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                    .addPaymentMethodType("card")
                    .build();
            SetupIntent intent = SetupIntent.create(params);
            return new SetupCommissionMethodResponse(intent.getClientSecret());
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create SetupIntent", e);
        }
    }

    public CommissionMethodResponse getCommissionMethod(UUID userId) {
        UserEntity user = userRepo.findById(userId).orElseThrow();
        if (user.getCommissionPaymentMethodId() == null) return null;

        YearMonth cardExp = YearMonth.of(user.getCommissionCardExpYear(), user.getCommissionCardExpMonth());
        YearMonth now = YearMonth.from(LocalDate.now(clock));
        long monthsUntilExpiry = now.until(cardExp, ChronoUnit.MONTHS);

        ExpirationStatus status;
        if (cardExp.isBefore(now)) {
            status = ExpirationStatus.EXPIRED;
        } else if (monthsUntilExpiry <= 1) {
            status = ExpirationStatus.EXPIRES_SOON;
        } else {
            status = ExpirationStatus.VALID;
        }

        return new CommissionMethodResponse(
                user.getCommissionCardBrand(),
                user.getCommissionCardLast4(),
                user.getCommissionCardExpMonth(),
                user.getCommissionCardExpYear(),
                status);
    }

    @Transactional
    public void detachCommissionMethod(UUID userId) {
        UserEntity user = userRepo.findById(userId).orElseThrow();
        if (user.getCommissionPaymentMethodId() == null) return;

        String pmId = user.getCommissionPaymentMethodId();
        try {
            PaymentMethod pm = PaymentMethod.retrieve(pmId);
            pm.detach();
        } catch (StripeException e) {
            log.warn("Stripe detach failed for PM {}, cleaning up DB anyway: {}", pmId, e.getMessage());
        }

        user.setCommissionPaymentMethodId(null);
        user.setCommissionCardBrand(null);
        user.setCommissionCardLast4(null);
        user.setCommissionCardExpMonth(null);
        user.setCommissionCardExpYear(null);
        userRepo.save(user);

        events.publishEvent(new CommissionMethodDetachedEvent(userId));
    }

    // --- Commission charging ---

    @Transactional
    public AcceptBidResponse chargeCommission(BidEntity bid, UUID travelerId) {
        if (bid.getCommissionStatus() == CommissionStatus.CHARGED) {
            return AcceptBidResponse.accepted();
        }

        UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() == null) {
            throw new CommissionMethodMissingException();
        }

        AnnouncementEntity announcement = announcementRepo.findById(bid.getAnnouncementId()).orElseThrow();
        BigDecimal cashAmount = bid.getWeightKg().multiply(announcement.getPricePerKg());
        BigDecimal commission = computeCommission(cashAmount);
        long amountCents = commission.multiply(new BigDecimal(100)).longValueExact();
        String idempotencyKey = "bid_accept_" + bid.getId() + "_v" + bid.getCommissionRetryCount();

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency("eur")
                    .setCustomer(traveler.getStripeCustomerId())
                    .setPaymentMethod(traveler.getCommissionPaymentMethodId())
                    .setOffSession(true)
                    .setConfirm(true)
                    .setDescription("Commission cash bid " + bid.getId())
                    .putMetadata("bid_id", bid.getId().toString())
                    .putMetadata("commission_purpose", "cash_bid")
                    .build();
            RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
            PaymentIntent pi = PaymentIntent.create(params, opts);

            bid.setCommissionPaymentIntentId(pi.getId());

            return switch (pi.getStatus()) {
                case "succeeded" -> {
                    bid.setCommissionStatus(CommissionStatus.CHARGED);
                    bid.setCommissionChargedVia(CommissionChargedVia.CARD);
                    bidRepo.save(bid);
                    yield AcceptBidResponse.accepted();
                }
                case "requires_action" -> {
                    bid.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
                    bidRepo.save(bid);
                    yield AcceptBidResponse.requires3ds(pi.getClientSecret(), pi.getId());
                }
                default -> {
                    bid.setCommissionStatus(CommissionStatus.FAILED);
                    bidRepo.save(bid);
                    yield AcceptBidResponse.failed("Statut PaymentIntent inattendu : " + pi.getStatus());
                }
            };
        } catch (CardException e) {
            bid.setCommissionStatus(CommissionStatus.FAILED);
            bid.setCommissionRetryCount(bid.getCommissionRetryCount() + 1);
            bidRepo.save(bid);
            // e.getCode() can be null for some Stripe error subtypes — guard before switch
            String userMessage = switch (e.getCode() != null ? e.getCode() : "") {
                case "expired_card" -> "Votre carte de commission est expirée.";
                case "insufficient_funds" -> "Fonds insuffisants sur votre carte de commission.";
                case "authentication_required" -> "Votre carte nécessite une authentification supplémentaire.";
                default -> "Votre carte de commission a été refusée.";
            };
            return AcceptBidResponse.failed(userMessage);
        } catch (StripeException e) {
            throw new CommissionChargeFailedException("Erreur Stripe lors du débit de commission", e);
        }
    }

    /**
     * Prélève la commission depuis le wallet du voyageur.
     * Garde idempotente : vérifie qu'aucune transaction COMMISSION_DEDUCTED n'existe déjà
     * pour ce bid (WalletService.debit n'est pas idempotent en lui-même).
     * Pose commissionStatus=CHARGED et commissionChargedVia=WALLET.
     */
    @Transactional
    public void chargeCommissionFromWallet(BidEntity bid, UUID travelerId, BigDecimal commission) {
        if (walletTransactionRepository.existsByUserIdAndBidIdAndType(
                travelerId, bid.getId(), WalletTransactionType.COMMISSION_DEDUCTED)) {
            log.info("Commission wallet déjà prélevée pour bid {}, idempotent skip", bid.getId());
            return;
        }
        walletService.debit(travelerId, commission, WalletTransactionType.COMMISSION_DEDUCTED, bid.getId());
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionChargedVia(CommissionChargedVia.WALLET);
        bidRepo.save(bid);
        auditService.log("payment", bid.getId(), "COMMISSION_CHARGED_WALLET",
                travelerId, Map.of("commission", commission.toPlainString()));
    }

    /**
     * Prélève la commission automatiquement : wallet en priorité, carte en fallback.
     * Utilisé par les flux asynchrones (mobile money) où il n'y a pas d'interaction utilisateur.
     * Si ni wallet ni carte ne sont disponibles ou si la carte nécessite 3DS (impossible en async),
     * pose commissionStatus=FAILED et logue un audit (créance à recouvrer).
     */
    @Transactional
    public void chargeCommissionAuto(BidEntity bid, UUID travelerId) {
        AnnouncementEntity announcement = announcementRepo.findById(bid.getAnnouncementId()).orElseThrow();
        BigDecimal commission = computeBidCommission(bid, announcement);

        // 1) Wallet prioritaire
        BigDecimal balance = walletService.getBalance(travelerId);
        if (balance.compareTo(commission) >= 0) {
            try {
                chargeCommissionFromWallet(bid, travelerId, commission);
                return;
            } catch (InsufficientWalletBalanceException e) {
                // Race TOCTOU : solde a chuté entre getBalance et debit → fallback carte
                log.warn("Race TOCTOU wallet pour bid {} — fallback carte", bid.getId());
            }
        }

        // 2) Fallback carte automatique
        UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() != null) {
            try {
                AcceptBidResponse r = chargeCommission(bid, travelerId);
                if (r.status() == AcceptanceStatusDto.ACCEPTED) {
                    bid.setCommissionChargedVia(CommissionChargedVia.CARD);
                    bidRepo.save(bid);
                    return;
                }
                // REQUIRES_3DS impossible en async → créance
                log.warn("Commission carte bid {} : statut={} en async → créance commission",
                        bid.getId(), r.status());
            } catch (RuntimeException e) {
                // Erreur Stripe transitoire (panne API, timeout, rate limit) — créance.
                // On NE laisse PAS l'exception remonter pour ne pas rollback la tx REQUIRES_NEW
                // du listener MM (qui a déjà commité le paiement principal).
                log.error("Commission carte async échouée pour bid {} : {}", bid.getId(), e.getMessage());
            }
        }

        // 3) Ni wallet ni carte disponible/valide → créance
        bid.setCommissionStatus(CommissionStatus.FAILED);
        bidRepo.save(bid);
        auditService.log("payment", bid.getId(), "COMMISSION_AUTO_FAILED", travelerId,
                Map.of("reason", "no-wallet-no-card"));
        log.error("Commission auto impossible pour bid {} travelerId {} — ni wallet ni carte", bid.getId(), travelerId);
    }

    // --- Cash bid acceptance ---

    @Transactional
    public AcceptBidResponse acceptCashBid(UUID bidId, UUID travelerId, CommissionSource commissionSource) {
        BidEntity bid = bidRepo.findByIdForUpdate(bidId)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepo.findByIdForUpdate(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(travelerId)) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN,
                    "forbidden", "Forbidden", "Ce trajet ne vous appartient pas");
        }
        if (!announcement.getAcceptedPaymentMethods().contains(com.dony.api.payments.cash.PaymentMethod.CASH)) {
            throw new InvalidPaymentMethodForAnnouncementException(
                    "Cette annonce n'accepte pas le paiement en espèces");
        }
        if (bid.getPaymentMethod() != com.dony.api.payments.cash.PaymentMethod.CASH) {
            throw new InvalidPaymentMethodForAnnouncementException(
                    "Ce bid n'est pas un bid cash");
        }
        if (bid.getStatus() == BidStatus.ACCEPTED
                && bid.getCommissionStatus() == CommissionStatus.CHARGED) {
            return AcceptBidResponse.accepted();
        }
        if (bid.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                    "capacity-insufficient", "Insufficient Capacity",
                    "Capacité insuffisante pour accepter cette demande");
        }

        BigDecimal commission = computeBidCommission(bid, announcement);

        if (commissionSource == CommissionSource.WALLET_FIRST) {
            BigDecimal balance = walletService.getBalance(travelerId);
            if (balance.compareTo(commission) >= 0) {
                try {
                    chargeCommissionFromWallet(bid, travelerId, commission);
                    finalizeBidAcceptance(bid, announcement, travelerId);
                    return AcceptBidResponse.accepted();
                } catch (InsufficientWalletBalanceException e) {
                    // Race TOCTOU : solde a chuté entre getBalance et debit
                    balance = e.getAvailableBalance();
                }
            }
            // Solde insuffisant → informer le voyageur
            UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
            boolean hasCard = traveler.getCommissionPaymentMethodId() != null;
            return AcceptBidResponse.insufficientWallet(balance, commission, hasCard);
        }

        // commissionSource == CARD → comportement carte existant
        AcceptBidResponse response = chargeCommission(bid, travelerId);
        if (response.status() == AcceptanceStatusDto.ACCEPTED) {
            bid.setCommissionChargedVia(CommissionChargedVia.CARD);
            bidRepo.save(bid);
            finalizeBidAcceptance(bid, announcement, travelerId);
        }
        return response;
    }

    @Transactional
    public ConfirmAcceptanceResponse confirmCommissionAcceptance(UUID bidId) {
        BidEntity bid = bidRepo.findById(bidId).orElseThrow();
        if (bid.getCommissionStatus() == CommissionStatus.CHARGED
                && bid.getStatus() == BidStatus.ACCEPTED) {
            return ConfirmAcceptanceResponse.ok();
        }
        if (bid.getCommissionStatus() == CommissionStatus.CHARGED) {
            // Pose CARD si absent (idempotent — un bid CHARGED via PI a toujours une carte)
            if (bid.getCommissionChargedVia() == null && bid.getCommissionPaymentIntentId() != null) {
                bid.setCommissionChargedVia(CommissionChargedVia.CARD);
            }
            AnnouncementEntity announcement = announcementRepo.findByIdForUpdate(bid.getAnnouncementId()).orElseThrow();
            finalizeBidAcceptance(bid, announcement, announcement.getTravelerId());
            return ConfirmAcceptanceResponse.ok();
        }
        if (bid.getCommissionPaymentIntentId() == null) {
            return ConfirmAcceptanceResponse.fail("Aucun PaymentIntent à confirmer.");
        }
        try {
            PaymentIntent pi = PaymentIntent.retrieve(bid.getCommissionPaymentIntentId());
            if ("succeeded".equals(pi.getStatus())) {
                bid.setCommissionStatus(CommissionStatus.CHARGED);
                bid.setCommissionChargedVia(CommissionChargedVia.CARD);
                AnnouncementEntity announcement = announcementRepo.findByIdForUpdate(bid.getAnnouncementId()).orElseThrow();
                finalizeBidAcceptance(bid, announcement, announcement.getTravelerId());
                return ConfirmAcceptanceResponse.ok();
            }
            bid.setCommissionStatus(CommissionStatus.FAILED);
            bidRepo.save(bid);
            return ConfirmAcceptanceResponse.fail("PaymentIntent status: " + pi.getStatus());
        } catch (StripeException e) {
            return ConfirmAcceptanceResponse.fail("Erreur Stripe : " + e.getMessage());
        }
    }

    /**
     * Rembourse la commission en créditant le wallet du voyageur.
     * Utilisé quand commissionChargedVia=WALLET et qu'un remboursement est dû.
     * Clé d'idempotence intentionnellement distincte selon le déclencheur :
     * les appelants passent la clé appropriée (noshow vs trip-cancel).
     */
    @Transactional
    public void refundCommissionToWallet(BidEntity bid, UUID travelerId, String idempotencyKey) {
        if (bid.getCommissionStatus() == CommissionStatus.REFUNDED) return;
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) {
            log.warn("refundCommissionToWallet called on bid {} with status {}", bid.getId(), bid.getCommissionStatus());
            return;
        }
        Optional<com.dony.api.payments.wallet.WalletTransactionEntity> commissionTx =
                walletTransactionRepository.findByUserIdAndBidIdAndType(
                        travelerId, bid.getId(), WalletTransactionType.COMMISSION_DEDUCTED);
        if (commissionTx.isEmpty()) {
            log.warn("refundCommissionToWallet: aucune tx COMMISSION_DEDUCTED pour bid {} traveler {}", bid.getId(), travelerId);
            return;
        }
        BigDecimal refundAmount = commissionTx.get().getAmount().abs();
        walletService.credit(travelerId, refundAmount, com.dony.api.payments.wallet.WalletTransactionType.REFUND,
                "refund-" + bid.getId(), idempotencyKey);
        bid.setCommissionStatus(CommissionStatus.REFUNDED);
        bidRepo.save(bid);
        auditService.log("payment", bid.getId(), "COMMISSION_REFUNDED_TO_WALLET",
                travelerId, Map.of("amount", refundAmount.toPlainString(), "idempotencyKey", idempotencyKey));
    }

    @Transactional
    public void refundCommission(BidEntity bid) {
        if (bid.getCommissionStatus() == CommissionStatus.REFUNDED) return;
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) {
            log.warn("refundCommission called on bid {} with status {}", bid.getId(), bid.getCommissionStatus());
            return;
        }
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(bid.getCommissionPaymentIntentId())
                    .build();
            RequestOptions opts = RequestOptions.builder()
                    .setIdempotencyKey("bid_refund_" + bid.getId())
                    .build();
            Refund.create(params, opts);
            bid.setCommissionStatus(CommissionStatus.REFUNDED);
            bidRepo.save(bid);
        } catch (StripeException e) {
            bid.setCommissionStatus(CommissionStatus.REFUND_FAILED);
            bidRepo.save(bid);
            log.error("Refund failed for bid {}: {}", bid.getId(), e.getMessage());
        }
    }

    // --- Private helpers ---

    private void finalizeBidAcceptance(BidEntity bid, AnnouncementEntity announcement, UUID travelerId) {
        if (bid.getStatus() == BidStatus.ACCEPTED) return;

        bid.setStatus(BidStatus.ACCEPTED);
        if (bid.getQrToken() == null) bid.setQrToken(UUID.randomUUID().toString());
        if (bid.getTrackingToken() == null) bid.setTrackingToken(UUID.randomUUID().toString());
        if (bid.getTrackingNumber() == null) bid.setTrackingNumber(generateTrackingNumber());

        announcement.setAvailableKg(announcement.getAvailableKg().subtract(bid.getWeightKg()));
        if (announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0) {
            announcement.setStatus(AnnouncementStatus.FULL);
        }
        announcementRepo.save(announcement);
        bidRepo.save(bid);

        events.publishEvent(new BidAcceptedEvent(
                bid.getId(), bid.getSenderId(), travelerId, bid.getAnnouncementId()));
        log.info("Cash bid {} finalized as ACCEPTED for traveler {}", bid.getId(), travelerId);
    }

    private String generateTrackingNumber() {
        StringBuilder sb = new StringBuilder("DON-");
        for (int i = 0; i < 8; i++) {
            sb.append(TRACKING_CHARS.charAt(SECURE_RNG.nextInt(TRACKING_CHARS.length())));
        }
        return sb.toString();
    }

    private void ensureStripeCustomer(UserEntity user) {
        if (user.getStripeCustomerId() != null) return;
        try {
            Customer c = Customer.create(CustomerCreateParams.builder()
                    .setEmail(user.getEmail())
                    .putMetadata("dony_user_id", user.getId().toString())
                    .build());
            user.setStripeCustomerId(c.getId());
            userRepo.save(user);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create Stripe customer", e);
        }
    }
}
