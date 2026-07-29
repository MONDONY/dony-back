package com.yadony.api.tracking.events;

import java.util.UUID;

/**
 * Published by the tracking service when a delivery is confirmed.
 * The payments package listens to this event to capture the escrow.
 */
public class DeliveryConfirmedEvent {

    private final UUID bidId;
    private final UUID senderId;
    private final UUID travelerId;

    public DeliveryConfirmedEvent(UUID bidId, UUID senderId, UUID travelerId) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
    }

    public UUID getBidId()      { return bidId; }
    public UUID getSenderId()   { return senderId; }
    public UUID getTravelerId() { return travelerId; }
}
