package com.dony.api.payments.mobilemoney;

import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.money.CountryCurrencies;
import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.common.money.MinorUnits;
import com.dony.api.common.money.Money;
import com.dony.api.common.money.MoneyConversion;
import com.dony.api.common.money.MoneyRounding;
import com.dony.api.common.money.PeggedFxRateProvider;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.CashCommissionService;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MobileMoneyPaymentService {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPaymentService.class);

    private final MobileMoneyPaymentRepository repository;
    private final MobileMoneyGatewayRegistry registry;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final ApplicationEventPublisher events;
    private final AuditService auditService;
    private final CashCommissionService cashCommissionService;
    private final PeggedFxRateProvider peggedFxRateProvider;
    private final CurrencyRegistry currencyRegistry;

    public MobileMoneyPaymentService(MobileMoneyPaymentRepository repository,
                                     MobileMoneyGatewayRegistry registry,
                                     BidRepository bidRepository,
                                     AnnouncementRepository announcementRepository,
                                     ApplicationEventPublisher events,
                                     AuditService auditService,
                                     CashCommissionService cashCommissionService,
                                     PeggedFxRateProvider peggedFxRateProvider,
                                     CurrencyRegistry currencyRegistry) {
        this.repository             = repository;
        this.registry               = registry;
        this.bidRepository          = bidRepository;
        this.announcementRepository = announcementRepository;
        this.events                 = events;
        this.auditService           = auditService;
        this.cashCommissionService  = cashCommissionService;
        this.peggedFxRateProvider   = peggedFxRateProvider;
        this.currencyRegistry       = currencyRegistry;
    }

    /**
     * Initiate (or retrieve an existing non-expired) Mobile Money payment for a bid.
     * Idempotent: returns the existing PENDING entity if it has not yet expired.
     * Only the sender of the bid is allowed to initiate a Mobile Money payment.
     */
    @Transactional
    public MobileMoneyPaymentEntity initiate(UUID bidId, UUID callerId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Offre introuvable : " + bidId));

        if (!bid.getSenderId().equals(callerId)) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "access-denied", "Access Denied",
                    "Vous n'êtes pas l'expéditeur de ce bid");
        }

        PaymentMethod pm = bid.getPaymentMethod();

        if (!registry.isMobileMoneyProvider(pm)) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "bid-not-mobile-money", "Not Mobile Money Bid",
                    "Ce bid n'utilise pas un mode de paiement Mobile Money");
        }

        // Idempotence: return existing PENDING payment if still valid
        Optional<MobileMoneyPaymentEntity> existing =
                repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId);
        if (existing.isPresent()) {
            MobileMoneyPaymentEntity e = existing.get();
            if ("PENDING".equals(e.getStatus()) && e.getExpiresAt() != null
                    && e.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                return e;
            }
        }

        // Resolve travelerId via announcement
        var announcement = announcementRepository
                .findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable : " + bid.getAnnouncementId()));
        UUID travelerId = announcement.getTravelerId();

        MobileMoneyGateway gateway = registry.getGateway(pm);

        // CORRECTIF (audit F1) : le principal MM = NET du bid (ce que l'expéditeur
        // paie au voyageur), plus jamais declaredValueEur (valeur d'assurance).
        BigDecimal netEur = cashCommissionService.computeBidNet(bid, announcement);

        // CORRECTIF (audit F4, règle R4) : devise du wallet ciblé, plus de XOF en dur.
        String walletCurrency = CountryCurrencies.forCountry(bid.getMobileMoneyCountryCode())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "unsupported-mm-country", "Unsupported Mobile Money Country",
                        "Pays non couvert par le mobile money : " + bid.getMobileMoneyCountryCode()));

        // Gel du montant local (règle R2) : parité en base + arrondi transactionnel.
        MoneyConversion conv = peggedFxRateProvider.convert(new Money(netEur, "EUR"), walletCurrency)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "currency-not-convertible", "Currency Not Convertible",
                        "Aucune parité en base pour " + walletCurrency));
        long amountMinor = MoneyRounding.roundTransactionalMinor(
                MinorUnits.toMinor(conv.target(), currencyRegistry),
                currencyRegistry.roundingIncrementOf(walletCurrency));
        BigDecimal localAmount = MinorUnits.fromMinor(amountMinor, walletCurrency, currencyRegistry).amount();

        MobileMoneyPaymentRequest req = new MobileMoneyPaymentRequest(
                bidId, bid.getMobileMoneyPhone(), bid.getMobileMoneyCountryCode(),
                localAmount, walletCurrency);

        MobileMoneyLinkResult result = gateway.generatePaymentLink(req);

        MobileMoneyPaymentEntity entity = new MobileMoneyPaymentEntity();
        entity.setBidId(bidId);
        entity.setTravelerId(travelerId);
        entity.setProvider(pm.name());
        entity.setCountryCode(bid.getMobileMoneyCountryCode());
        entity.setPhoneNumber(bid.getMobileMoneyPhone());
        entity.setAmount(netEur);                 // EUR contractuel
        entity.setCurrency(walletCurrency);        // devise du wallet (R4)
        entity.setAmountMinor(amountMinor);        // montant local GELÉ (R2)
        entity.setFxRate(conv.rate());
        entity.setRateSource(conv.rateSource());
        entity.setExternalReference(result.externalReference());
        entity.setPaymentLink(result.paymentLink());
        entity.setStatus("PENDING");
        entity.setExpiresAt(result.expiresAt());

        auditService.log("MM_PAYMENT", bidId, "AMOUNT_FROZEN", callerId,
                Map.of("amountEur", netEur.toPlainString(),
                       "amountMinor", String.valueOf(amountMinor),
                       "currency", walletCurrency,
                       "fxRate", conv.rate().toPlainString(),
                       "rateSource", conv.rateSource()));

        return repository.save(entity);
    }

    /**
     * Handle an incoming Mobile Money webhook.
     * Validates the signature, then marks the payment COMPLETED or FAILED,
     * and publishes a BidPaidByMobileMoneyEvent on success.
     * Idempotent: if the payment is already COMPLETED, no-op.
     */
    @Transactional
    public void handleWebhook(PaymentMethod provider, String rawPayload, String signatureHeader) {
        MobileMoneyGateway gateway = registry.getGateway(provider);

        if (!gateway.verifyWebhookSignature(rawPayload, signatureHeader)) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "invalid-mm-signature", "Invalid Signature",
                    "Signature webhook Mobile Money invalide");
        }

        String externalRef = gateway.extractExternalReference(rawPayload);
        if (externalRef == null) {
            log.warn("MM webhook {}: cannot extract reference from payload", provider);
            return;
        }

        Optional<MobileMoneyPaymentEntity> opt = repository.findByExternalReference(externalRef);
        if (opt.isEmpty()) {
            log.warn("MM webhook {}: no payment found for ref={}", provider, externalRef);
            return;
        }

        MobileMoneyPaymentEntity payment = opt.get();

        // Idempotence: already processed
        if ("COMPLETED".equals(payment.getStatus())) {
            log.info("MM webhook {}: payment ref={} already COMPLETED (idempotent)", provider, externalRef);
            return;
        }

        payment.setWebhookReceivedAt(LocalDateTime.now(ZoneOffset.UTC));

        if (gateway.isPaymentConfirmed(rawPayload)) {
            payment.setStatus("COMPLETED");
            // Règlement constaté (spec devise §4.3). Stub : le gateway ne rapporte pas
            // encore le montant réellement débité → on constate le montant gelé
            // (amountMinor, posé à l'initiation — cf. règle R2). Au branchement d'un
            // PSP réel (Task 12) : remplacer par le montant effectivement rapporté par le PSP.
            payment.setSettledAmountMinor(payment.getAmountMinor());
            repository.save(payment);
            auditService.log(
                    "MM_PAYMENT",
                    payment.getId(),
                    "PAYMENT_COMPLETED",
                    payment.getTravelerId(),
                    Map.of("provider", provider.name(), "bidId", payment.getBidId().toString()));
            events.publishEvent(new BidPaidByMobileMoneyEvent(payment.getBidId(), payment.getTravelerId()));
            log.info("MM webhook {}: ref={} COMPLETED for bidId={}", provider, externalRef, payment.getBidId());
        } else {
            payment.setStatus("FAILED");
            payment.setFailureReason(gateway.extractFailureReason(rawPayload));
            repository.save(payment);
            auditService.log(
                    "MM_PAYMENT",
                    payment.getId(),
                    "PAYMENT_FAILED",
                    payment.getTravelerId(),
                    Map.of("provider", provider.name(),
                           "failureReason", payment.getFailureReason() != null ? payment.getFailureReason() : "",
                           "bidId", payment.getBidId().toString()));
            log.warn("MM webhook {}: ref={} FAILED: {}", provider, externalRef, payment.getFailureReason());
        }
    }

    /**
     * Returns the most recent Mobile Money payment for a bid, if any.
     * Only the sender or the traveler of the bid is allowed to view the payment status.
     * If the payment is PENDING and past its expiry, it is automatically marked EXPIRED.
     */
    @Transactional
    public Optional<MobileMoneyPaymentEntity> getStatus(UUID bidId, UUID callerId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Offre introuvable : " + bidId));

        var announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable : " + bid.getAnnouncementId()));

        boolean isSender   = bid.getSenderId().equals(callerId);
        boolean isTraveler = announcement.getTravelerId().equals(callerId);
        if (!isSender && !isTraveler) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "access-denied", "Access Denied",
                    "Vous n'avez pas accès à ce paiement");
        }

        Optional<MobileMoneyPaymentEntity> opt =
                repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId);
        opt.ifPresent(payment -> {
            if ("PENDING".equals(payment.getStatus())
                    && payment.getExpiresAt() != null
                    && payment.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
                payment.setStatus("EXPIRED");
                repository.save(payment);
                log.info("MobileMoneyPaymentService: payment {} marked EXPIRED for bidId={}", payment.getId(), bidId);
            }
        });
        return opt;
    }
}
