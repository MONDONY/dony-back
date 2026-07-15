package com.dony.api.disputes.events;

import java.util.UUID;

public class DisputeOpenedEvent {
    private final UUID bidId;
    private final UUID senderId;
    private final UUID travelerId;
    private final String type;

    /** Rétrocompatible : litige no-show départ (seul type existant avant cette feature). */
    public DisputeOpenedEvent(UUID bidId, UUID senderId, UUID travelerId) {
        this(bidId, senderId, travelerId, "SENDER_NO_SHOW_CONTESTED");
    }

    public DisputeOpenedEvent(UUID bidId, UUID senderId, UUID travelerId, String type) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
        this.type = type;
    }

    public UUID getBidId()      { return bidId; }
    public UUID getSenderId()   { return senderId; }
    public UUID getTravelerId() { return travelerId; }
    public String getType()     { return type; }
}
