package com.yadony.api.matching.events;

import java.util.UUID;

public class BidRejectedEvent {
    private final UUID bidId;
    private final UUID senderId;
    private final String reason;
    private final UUID announcementId;
    private final boolean rematchEligible;

    public BidRejectedEvent(UUID bidId, UUID senderId, String reason) {
        this(bidId, senderId, reason, null, false);
    }

    public BidRejectedEvent(UUID bidId, UUID senderId, String reason,
                             UUID announcementId, boolean rematchEligible) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.reason = reason;
        this.announcementId = announcementId;
        this.rematchEligible = rematchEligible;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public String getReason() { return reason; }
    public UUID getAnnouncementId() { return announcementId; }
    public boolean isRematchEligible() { return rematchEligible; }
}
