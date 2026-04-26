package com.dony.api.disputes.events;

import java.util.UUID;

public class DisputeOpenedEvent {
    private final UUID disputeId;
    private final UUID bidId;
    private final UUID senderId;
    private final UUID travelerId;

    public DisputeOpenedEvent(UUID disputeId, UUID bidId, UUID senderId, UUID travelerId) {
        this.disputeId = disputeId;
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
    }

    public UUID getDisputeId()  { return disputeId; }
    public UUID getBidId()      { return bidId; }
    public UUID getSenderId()   { return senderId; }
    public UUID getTravelerId() { return travelerId; }
}
