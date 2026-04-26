package com.dony.api.payments.events;

import java.math.BigDecimal;
import java.util.UUID;

public class PaymentReleasedEvent {
    private final UUID bidId;
    private final UUID travelerId;
    private final UUID senderId;
    private final BigDecimal amount;

    public PaymentReleasedEvent(UUID bidId, UUID travelerId, UUID senderId, BigDecimal amount) {
        this.bidId = bidId;
        this.travelerId = travelerId;
        this.senderId = senderId;
        this.amount = amount;
    }

    public UUID getBidId()      { return bidId; }
    public UUID getTravelerId() { return travelerId; }
    public UUID getSenderId()   { return senderId; }
    public BigDecimal getAmount(){ return amount; }
}
