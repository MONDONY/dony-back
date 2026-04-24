package com.dony.api.cancellation.events;

import java.util.List;
import java.util.UUID;

public class TripCancelledEvent {
    private final UUID announcementId;
    private final UUID travelerId;
    private final List<UUID> affectedSenderIds;
    private final String reason;

    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason) {
        this.announcementId = announcementId;
        this.travelerId = travelerId;
        this.affectedSenderIds = affectedSenderIds;
        this.reason = reason;
    }

    public UUID getAnnouncementId() { return announcementId; }
    public UUID getTravelerId() { return travelerId; }
    public List<UUID> getAffectedSenderIds() { return affectedSenderIds; }
    public String getReason() { return reason; }
}
