package com.dony.api.cancellation.events;

import java.util.UUID;

public class TravelerHighCancellationEvent {
    private final UUID travelerId;
    private final int cancellationCount;
    private final UUID announcementId;

    public TravelerHighCancellationEvent(UUID travelerId, int cancellationCount, UUID announcementId) {
        this.travelerId = travelerId;
        this.cancellationCount = cancellationCount;
        this.announcementId = announcementId;
    }

    public UUID getTravelerId() { return travelerId; }
    public int getCancellationCount() { return cancellationCount; }
    public UUID getAnnouncementId() { return announcementId; }
}