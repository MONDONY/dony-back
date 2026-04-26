package com.dony.api.matching.events;

import java.util.UUID;

public class ParcelRefusedEvent {

    private final UUID bidId;
    private final UUID travelerId;
    private final UUID senderId;
    private final String reason;

    public ParcelRefusedEvent(UUID bidId, UUID travelerId, UUID senderId, String reason) {
        this.bidId = bidId;
        this.travelerId = travelerId;
        this.senderId = senderId;
        this.reason = reason;
    }

    public UUID getBidId() { return bidId; }
    public UUID getTravelerId() { return travelerId; }
    public UUID getSenderId() { return senderId; }
    public String getReason() { return reason; }
}
