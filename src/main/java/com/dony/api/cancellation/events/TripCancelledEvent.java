package com.dony.api.cancellation.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TripCancelledEvent {
    private final UUID announcementId;
    private final UUID travelerId;
    private final List<UUID> affectedSenderIds;
    private final String reason;
    /** Story 6.7 — bid IDs whose escrow must be refunded. */
    private final List<UUID> affectedBidIds;
    /**
     * Task 7 — paymentMethod per bid so wallet listeners don't need BidRepository.
     * Key = bidId, value = PaymentMethod name ("CASH", "STRIPE", …).
     */
    private final Map<UUID, String> bidPaymentMethods;

    /** Full constructor (used by CancellationService). */
    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason,
                               List<UUID> affectedBidIds,
                               Map<UUID, String> bidPaymentMethods) {
        this.announcementId = announcementId;
        this.travelerId = travelerId;
        this.affectedSenderIds = affectedSenderIds;
        this.reason = reason;
        this.affectedBidIds = affectedBidIds;
        this.bidPaymentMethods = bidPaymentMethods != null ? bidPaymentMethods : Map.of();
    }

    /** Backward-compatible constructor — bidPaymentMethods defaults to empty map. */
    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason,
                               List<UUID> affectedBidIds) {
        this(announcementId, travelerId, affectedSenderIds, reason, affectedBidIds, Map.of());
    }

    public UUID getAnnouncementId() { return announcementId; }
    public UUID getTravelerId() { return travelerId; }
    public List<UUID> getAffectedSenderIds() { return affectedSenderIds; }
    public String getReason() { return reason; }
    public List<UUID> getAffectedBidIds() { return affectedBidIds; }
    public Map<UUID, String> getBidPaymentMethods() { return bidPaymentMethods; }
}
