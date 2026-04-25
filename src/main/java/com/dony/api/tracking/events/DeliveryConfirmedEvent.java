package com.dony.api.tracking.events;

import java.util.UUID;

/**
 * Published by the tracking service when a delivery is confirmed.
 * The payments package listens to this event to capture the escrow.
 */
public class DeliveryConfirmedEvent {

    private final UUID bidId;

    public DeliveryConfirmedEvent(UUID bidId) {
        this.bidId = bidId;
    }

    public UUID getBidId() {
        return bidId;
    }
}
