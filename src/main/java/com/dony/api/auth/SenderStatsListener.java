package com.dony.api.auth;

import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Optional;

/**
 * Increments {@code users.total_shipments} for the sender each time one of their bids
 * reaches COMPLETED. One bid delivered = +1, regardless of how many bids the sender
 * has on the same announcement.
 *
 * <p>Listens AFTER_COMMIT so a rollback of the originating tracking transaction does
 * not produce a phantom increment. Idempotence is enforced via {@code bids.shipment_counted}.
 */
@Component
public class SenderStatsListener {

    private static final Logger log = LoggerFactory.getLogger(SenderStatsListener.class);

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final AuditService auditService;

    public SenderStatsListener(UserRepository userRepository,
                               BidRepository bidRepository,
                               AuditService auditService) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        Optional<BidEntity> bidOpt = bidRepository.findById(event.getBidId());
        if (bidOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent received for unknown bidId={} — skipping total_shipments increment",
                    event.getBidId());
            return;
        }

        BidEntity bid = bidOpt.get();

        if (bid.isShipmentCounted()) {
            log.debug("shipment already counted for bidId={} — skipping (idempotent)", bid.getId());
            return;
        }

        // Defensive check: the event should only be published after the bid moves to COMPLETED.
        if (bid.getStatus() != BidStatus.COMPLETED) {
            log.warn("DeliveryConfirmedEvent received for bidId={} with status={} (expected COMPLETED) — skipping",
                    bid.getId(), bid.getStatus());
            return;
        }

        Optional<UserEntity> senderOpt = userRepository.findById(event.getSenderId());
        if (senderOpt.isEmpty()) {
            // Anomaly: a deleted/missing sender had a delivered parcel. Mark counted to stop
            // retries; log at ERROR for monitoring (Sentry) and manual investigation.
            log.error("DeliveryConfirmedEvent: sender {} not found for bidId={} — marking counted to stop retries; manual investigation required",
                    event.getSenderId(), bid.getId());
            bid.setShipmentCounted(true);
            bidRepository.save(bid);
            return;
        }

        UserEntity sender = senderOpt.get();
        int newTotal = sender.getTotalShipments() + 1;
        sender.setTotalShipments(newTotal);
        userRepository.save(sender);

        bid.setShipmentCounted(true);
        bidRepository.save(bid);

        auditService.log(
                "USER",
                sender.getId(),
                "TOTAL_SHIPMENTS_INCREMENTED",
                sender.getId(),
                Map.of(
                        "bidId", bid.getId().toString(),
                        "newTotal", String.valueOf(newTotal)
                )
        );

        log.info("total_shipments incremented for sender={} (bid={}, newTotal={})",
                sender.getId(), bid.getId(), newTotal);
    }
}
