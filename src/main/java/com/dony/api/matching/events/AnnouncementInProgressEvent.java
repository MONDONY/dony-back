package com.dony.api.matching.events;

import java.util.UUID;

public class AnnouncementInProgressEvent {

    private final UUID announcementId;
    private final UUID travelerId;

    public AnnouncementInProgressEvent(UUID announcementId, UUID travelerId) {
        this.announcementId = announcementId;
        this.travelerId = travelerId;
    }

    public UUID getAnnouncementId() { return announcementId; }
    public UUID getTravelerId() { return travelerId; }
}
