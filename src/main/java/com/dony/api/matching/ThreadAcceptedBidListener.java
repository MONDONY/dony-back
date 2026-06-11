package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidMaterializedEvent;
import com.dony.api.requests.event.PackageRequestAcceptedEvent;
import com.dony.api.requests.event.PackageRequestDetailsCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.UUID;

/**
 * Bridges the package_request marketplace flow to the classic bid-based
 * "Mes envois" infrastructure. When a NegotiationThread reaches ACCEPTED
 * (sender paid), we materialise an ACCEPTED Bid so the shipment shows up
 * in the sender's "Mes envois → En cours" tab and the rest of the flow
 * (tracking, livraison, capture Stripe) can keep using the existing Bid id.
 *
 * Listens AFTER_COMMIT in REQUIRES_NEW so we never see uncommitted state
 * from NegotiationService.finalizeAfterPayment, and we don't roll back the
 * whole acceptance if the bid creation hiccups.
 */
@Component
public class ThreadAcceptedBidListener {

    private static final Logger log = LoggerFactory.getLogger(ThreadAcceptedBidListener.class);

    private final BidRepository bidRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public ThreadAcceptedBidListener(BidRepository bidRepository, AuditService auditService,
                                     ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPackageRequestAccepted(PackageRequestAcceptedEvent e) {
        // Idempotence: if another listener (or a webhook retry) already
        // materialised the bid for this thread, do nothing.
        if (bidRepository.findByLinkedNegotiationThreadId(e.threadId()).isPresent()) {
            log.debug("Bid already exists for thread {}, skipping creation", e.threadId());
            return;
        }
        if (e.travelerAnnouncementId() == null) {
            log.warn("Cannot create bid for thread {}: travelerAnnouncementId is null", e.threadId());
            return;
        }

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(e.travelerAnnouncementId());
        bid.setSenderId(e.senderId());
        bid.setWeightKg(e.weightKg());
        bid.setDescription(e.description() != null ? e.description() : e.contentCategory());
        bid.setContentCategory(e.contentCategory());
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setPaymentIntentId(e.paymentIntentId());
        bid.setLinkedNegotiationThreadId(e.threadId());
        // Recipient details + disclaimer are completed by the sender before payment,
        // so they are carried on the event and set here at bid creation. If absent
        // (edited afterwards), onPackageRequestDetailsCompleted re-applies them.
        bid.setRecipientName(e.recipientName());
        bid.setRecipientPhone(e.recipientPhone());
        bid.setDeclaredValueEur(e.declaredValueEur());
        bid.setDisclaimerSignedAt(e.disclaimerSignedAt());
        bid.setDisclaimerSignedIp(e.disclaimerSignedIp());
        // Tracking artefacts generated at acceptance, mirroring BidService.acceptBid
        // so the marketplace-issued bid exposes the same QR + tracking number as a
        // classic bid (sender's "Mes envois" screen relies on these to render the
        // QR card and the recipient tracking link).
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingNumber(BidService.generateTrackingNumber());
        bid.setTrackingToken(UUID.randomUUID().toString());
        // Carry the negotiation thread's payment method onto the materialised bid so
        // the app shows the correct payment UI (a CASH bid must NOT prompt the sender
        // for a Stripe payment).
        if (e.paymentMethod() != null) {
            bid.setPaymentMethod(e.paymentMethod());
        }
        if (e.paymentMethod() == com.dony.api.payments.cash.PaymentMethod.CASH) {
            // Commission for cash negotiations is charged on the thread at finalize;
            // mark the bid so the classic cash flow never re-charges and the UI shows "réglée".
            bid.setCommissionStatus(com.dony.api.payments.cash.CommissionStatus.CHARGED);
        }

        BidEntity saved = bidRepository.save(bid);

        auditService.log("BID", saved.getId(), "CREATED_FROM_THREAD", e.senderId(),
            Map.of(
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString(),
                "agreedPrice", e.agreedPriceEur().toString()
            ));
        log.info("Bid {} created from thread {} (announcement {})",
            saved.getId(), e.threadId(), e.travelerAnnouncementId());

        // Notify requests/ so it can stamp materialized_bid_id on the thread and let
        // the mobile app open the bid detail (tracking, no-show…) from the thread.
        // Cross-package boundary: event only, never direct service injection.
        eventPublisher.publishEvent(new BidMaterializedEvent(e.threadId(), saved.getId()));
    }

    /**
     * After the sender fills the post-acceptance details on the package_request
     * (recipient + declared value + disclaimer), propagate them onto the
     * marketplace-issued bid so the existing flow (capture, etc.) sees them.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPackageRequestDetailsCompleted(PackageRequestDetailsCompletedEvent e) {
        var bidOpt = bidRepository.findByLinkedNegotiationThreadId(e.threadId());
        if (bidOpt.isEmpty()) {
            log.debug("No bid for thread {} yet — details will be re-applied on bid creation if it arrives later",
                e.threadId());
            return;
        }
        BidEntity bid = bidOpt.get();
        bid.setRecipientName(e.recipientName());
        bid.setRecipientPhone(e.recipientPhone());
        bid.setDeclaredValueEur(e.declaredValueEur());
        bid.setDisclaimerSignedAt(e.disclaimerSignedAt());
        bid.setDisclaimerSignedIp(e.disclaimerSignedIp());
        bidRepository.save(bid);
        log.info("Bid {} synced with package_request details (thread {})", bid.getId(), e.threadId());
    }
}
