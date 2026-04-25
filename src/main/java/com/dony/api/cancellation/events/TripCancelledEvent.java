package com.dony.api.cancellation.events;

import java.util.List;
import java.util.UUID;

public class TripCancelledEvent {
    private final UUID announcementId;
    private final UUID travelerId;
    private final List<UUID> affectedSenderIds;
    private final String reason;
    /** Story 6.7 — bid IDs whose escrow must be refunded. */
    private final List<UUID> affectedBidIds;

    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason,
                               List<UUID> affectedBidIds) {
        this.announcementId = announcementId;
        this.travelerId = travelerId;
        this.affectedSenderIds = affectedSenderIds;
        this.reason = reason;
        this.affectedBidIds = affectedBidIds;
    }

    public UUID getAnnouncementId() { return announcementId; }
    public UUID getTravelerId() { return travelerId; }
    public List<UUID> getAffectedSenderIds() { return affectedSenderIds; }
    public String getReason() { return reason; }
    public List<UUID> getAffectedBidIds() { return affectedBidIds; }
}
