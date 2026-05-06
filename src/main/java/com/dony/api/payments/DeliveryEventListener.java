package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.param.TransferCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Story 6.4 / bid-checkout-payment-first — Listens to DeliveryConfirmedEvent and
 * releases the Stripe escrow.
 *
 * Two paths depending on payment.legacy_destination_charge:
 *  - legacy=true  : destination charge model — capture the PaymentIntent (Stripe routes
 *                   funds to the traveler's Connect account via transfer_data set at PI
 *                   creation).
 *  - legacy=false : separate charges-and-transfers — the PI was already captured at
 *                   acceptation by BidAcceptedEventListener, so funds are on the platform
 *                   balance. Trigger a Transfer to the traveler's Connect account.
 *
 * Cross-package communication via Spring Events only.
 */
@Component
public class DeliveryEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventListener.class);

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public DeliveryEventListener(PaymentRepository paymentRepository,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDeliveryConfirmed(DeliveryConfirmedEvent event) {
        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(event.getBidId());

        if (paymentOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent received for bidId={} but no payment found — skipping",
                    event.getBidId());
            return;
        }

        PaymentEntity payment = paymentOpt.get();

        if (payment.getStatus() != PaymentStatus.ESCROW) {
            log.info("Payment {} for bid {} has status {} — skipping escrow release",
                    payment.getId(), event.getBidId(), payment.getStatus());
            return;
        }

        try {
            if (payment.isLegacyDestinationCharge()) {
                releaseLegacy(payment);
            } else {
                releaseV2(payment, event);
            }

            payment.setStatus(PaymentStatus.RELEASED);
            payment.setEscrowReleasedAt(LocalDateTime.now(ZoneOffset.UTC));
            paymentRepository.save(payment);

            String action = payment.isLegacyDestinationCharge()
                    ? "ESCROW_RELEASED_LEGACY"
                    : "ESCROW_RELEASED_TRANSFER";
            auditService.log(
                    "PAYMENT",
                    payment.getId(),
                    action,
                    payment.getBidId(),
                    Map.of(
                            "bidId", payment.getBidId().toString(),
                            "piId", payment.getStripePaymentIntentId(),
                            "amount", payment.getAmount().toPlainString(),
                            "legacy", String.valueOf(payment.isLegacyDestinationCharge())
                    )
            );

            log.info("Escrow released for payment {} (bid={}, legacy={})",
                    payment.getId(), event.getBidId(), payment.isLegacyDestinationCharge());

            // Notify traveler of payout (Story 8.2)
            eventPublisher.publishEvent(new PaymentReleasedEvent(
                    payment.getBidId(), event.getTravelerId(), event.getSenderId(), payment.getAmount()));

        } catch (StripeException e) {
            log.error("Escrow release failed for payment {} (bid={}, legacy={}): {}",
                    payment.getId(), event.getBidId(),
                    payment.isLegacyDestinationCharge(), e.getMessage(), e);
            // Do not rethrow — failure is logged, admin J+48 scheduler will catch it
        }
    }

    private void releaseLegacy(PaymentEntity payment) throws StripeException {
        // Old destination-charge model: capture the PaymentIntent. Stripe transfers
        // funds directly to the traveler's Connect account because transfer_data was
        // set at PaymentIntent creation.
        PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
        pi.capture();
    }

    private void releaseV2(PaymentEntity payment, DeliveryConfirmedEvent event) throws StripeException {
        // New separate-charges-and-transfers model: PI was already captured on the
        // platform balance at acceptation (BidAcceptedEventListener). Initiate a
        // Transfer to the traveler's Connect account.
        UserEntity traveler = userRepository.findById(event.getTravelerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Traveler not found: " + event.getTravelerId()));

        if (traveler.getStripeAccountId() == null || traveler.getStripeAccountId().isBlank()) {
            throw new IllegalStateException(
                    "Traveler " + traveler.getId() + " has no Stripe Connect account");
        }

        // TODO Q6 (spec bid-checkout-payment-first): si Stripe support révèle des frais
        // de Transfer non-nuls pour les comptes Connect en zone CFA, ajuster le calcul :
        //     net = total - commission - transferFees
        // Pour l'instant on assume Transfers EUR gratuits (zone SEPA / hypothèse MVP).
        BigDecimal net = payment.getAmount().subtract(payment.getCommissionAmount());
        long netCents = net.multiply(BigDecimal.valueOf(100)).longValueExact();

        TransferCreateParams.Builder builder = TransferCreateParams.builder()
                .setAmount(netCents)
                .setCurrency("eur")
                .setDestination(traveler.getStripeAccountId())
                .putMetadata("bid_id", event.getBidId().toString())
                .putMetadata("payment_id", payment.getId() != null ? payment.getId().toString() : "");

        if (payment.getStripeChargeId() != null && !payment.getStripeChargeId().isBlank()) {
            builder.setSourceTransaction(payment.getStripeChargeId());
        }

        Transfer.create(builder.build());
    }
}
