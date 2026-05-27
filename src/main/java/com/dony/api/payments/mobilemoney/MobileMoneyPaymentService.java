package com.dony.api.payments.mobilemoney;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
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

    public MobileMoneyPaymentService(MobileMoneyPaymentRepository repository,
                                     MobileMoneyGatewayRegistry registry,
                                     BidRepository bidRepository,
                                     AnnouncementRepository announcementRepository,
                                     ApplicationEventPublisher events) {
        this.repository             = repository;
        this.registry               = registry;
        this.bidRepository          = bidRepository;
        this.announcementRepository = announcementRepository;
        this.events                 = events;
    }

    /**
     * Initiate (or retrieve an existing non-expired) Mobile Money payment for a bid.
     * Idempotent: returns the existing PENDING entity if it has not yet expired.
     */
    @Transactional
    public MobileMoneyPaymentEntity initiate(UUID bidId) {
        BidEntity bid = bidRepository.findById(bidId).orElseThrow();
        PaymentMethod pm = bid.getPaymentMethod();

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
        UUID travelerId = announcementRepository
                .findById(bid.getAnnouncementId())
                .orElseThrow()
                .getTravelerId();

        MobileMoneyGateway gateway = registry.getGateway(pm);
        BigDecimal amount = bid.getDeclaredValueEur();

        MobileMoneyPaymentRequest req = new MobileMoneyPaymentRequest(
                bidId, bid.getMobileMoneyPhone(), bid.getMobileMoneyCountryCode(), amount, "XOF");

        MobileMoneyLinkResult result = gateway.generatePaymentLink(req);

        MobileMoneyPaymentEntity entity = new MobileMoneyPaymentEntity();
        entity.setBidId(bidId);
        entity.setTravelerId(travelerId);
        entity.setProvider(pm.name());
        entity.setCountryCode(bid.getMobileMoneyCountryCode());
        entity.setPhoneNumber(bid.getMobileMoneyPhone());
        entity.setAmount(amount);
        entity.setCurrency("XOF");
        entity.setExternalReference(result.externalReference());
        entity.setPaymentLink(result.paymentLink());
        entity.setStatus("PENDING");
        entity.setExpiresAt(result.expiresAt());

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
            repository.save(payment);
            events.publishEvent(new BidPaidByMobileMoneyEvent(payment.getBidId(), payment.getTravelerId()));
            log.info("MM webhook {}: ref={} COMPLETED for bidId={}", provider, externalRef, payment.getBidId());
        } else {
            payment.setStatus("FAILED");
            payment.setFailureReason(gateway.extractFailureReason(rawPayload));
            repository.save(payment);
            log.warn("MM webhook {}: ref={} FAILED: {}", provider, externalRef, payment.getFailureReason());
        }
    }

    /**
     * Returns the most recent Mobile Money payment for a bid, if any.
     */
    @Transactional(readOnly = true)
    public Optional<MobileMoneyPaymentEntity> getStatus(UUID bidId) {
        return repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId);
    }
}
