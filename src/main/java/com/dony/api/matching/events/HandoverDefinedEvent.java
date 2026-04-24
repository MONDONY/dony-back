package com.dony.api.matching.events;

import java.time.LocalDateTime;
import java.util.UUID;

public class HandoverDefinedEvent {
    private final UUID bidId;
    private final UUID senderId;
    private final String location;
    private final LocalDateTime windowStart;
    private final LocalDateTime windowEnd;

    public HandoverDefinedEvent(UUID bidId, UUID senderId, String location,
                                 LocalDateTime windowStart, LocalDateTime windowEnd) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.location = location;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public String getLocation() { return location; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
}
