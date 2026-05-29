package com.dony.api.payments.mobilemoney.events;

import java.util.UUID;

public class BidPaidByMobileMoneyEvent {
    private final UUID bidId;
    private final UUID travelerId;

    public BidPaidByMobileMoneyEvent(UUID bidId, UUID travelerId) {
        this.bidId = bidId;
        this.travelerId = travelerId;
    }

    public UUID getBidId() { return bidId; }
    public UUID getTravelerId() { return travelerId; }
}
