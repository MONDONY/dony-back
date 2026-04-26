package com.dony.api.matching.events;

import java.math.BigDecimal;
import java.util.UUID;

public class BidCreatedEvent {
    private final UUID bidId;
    private final UUID travelerId;
    private final UUID senderId;
    private final String senderFirstName;
    private final BigDecimal weightKg;
    private final String corridor;

    public BidCreatedEvent(UUID bidId, UUID travelerId, UUID senderId,
                           String senderFirstName, BigDecimal weightKg, String corridor) {
        this.bidId = bidId;
        this.travelerId = travelerId;
        this.senderId = senderId;
        this.senderFirstName = senderFirstName;
        this.weightKg = weightKg;
        this.corridor = corridor;
    }

    public UUID getBidId()            { return bidId; }
    public UUID getTravelerId()       { return travelerId; }
    public UUID getSenderId()         { return senderId; }
    public String getSenderFirstName(){ return senderFirstName; }
    public BigDecimal getWeightKg()   { return weightKg; }
    public String getCorridor()       { return corridor; }
}
