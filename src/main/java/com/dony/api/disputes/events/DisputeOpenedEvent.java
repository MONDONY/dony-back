package com.dony.api.disputes.events;

import java.util.UUID;

public class DisputeOpenedEvent {
    private final UUID bidId;
    private final UUID senderId;
    private final UUID travelerId;

    public DisputeOpenedEvent(UUID bidId, UUID senderId, UUID travelerId) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
    }

    public UUID getBidId()      { return bidId; }
    public UUID getSenderId()   { return senderId; }
    public UUID getTravelerId() { return travelerId; }
}
