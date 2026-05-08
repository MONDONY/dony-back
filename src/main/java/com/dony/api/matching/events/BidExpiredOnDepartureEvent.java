package com.dony.api.matching.events;

import java.util.UUID;

public class BidExpiredOnDepartureEvent {

    private final UUID bidId;
    private final UUID senderId;
    private final UUID announcementId;
    private final UUID travelerId;

    public BidExpiredOnDepartureEvent(UUID bidId, UUID senderId, UUID announcementId, UUID travelerId) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.announcementId = announcementId;
        this.travelerId = travelerId;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public UUID getAnnouncementId() { return announcementId; }
    public UUID getTravelerId() { return travelerId; }
}
