package com.dony.api.matching.events;

import java.util.UUID;

public class VoyageurNoShowEvent {

    private final UUID bidId;
    private final UUID travelerId;
    private final UUID senderId;
    private final int noShowCount;

    public VoyageurNoShowEvent(UUID bidId, UUID travelerId, UUID senderId, int noShowCount) {
        this.bidId = bidId;
        this.travelerId = travelerId;
        this.senderId = senderId;
        this.noShowCount = noShowCount;
    }

    public UUID getBidId() { return bidId; }
    public UUID getTravelerId() { return travelerId; }
    public UUID getSenderId() { return senderId; }
    public int getNoShowCount() { return noShowCount; }
}
