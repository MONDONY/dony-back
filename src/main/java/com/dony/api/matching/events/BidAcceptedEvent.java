package com.dony.api.matching.events;

import java.util.UUID;

public class BidAcceptedEvent {
    private final UUID bidId;
    private final UUID senderId;
    private final UUID travelerId;
    private final UUID announcementId;

    public BidAcceptedEvent(UUID bidId, UUID senderId, UUID travelerId, UUID announcementId) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
        this.announcementId = announcementId;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public UUID getTravelerId() { return travelerId; }
    public UUID getAnnouncementId() { return announcementId; }
}
