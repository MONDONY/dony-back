package com.dony.api.matching.events;

import java.util.UUID;

public class BidRejectedEvent {
    private final UUID bidId;
    private final UUID senderId;
    private final String reason;

    public BidRejectedEvent(UUID bidId, UUID senderId, String reason) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.reason = reason;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public String getReason() { return reason; }
}
