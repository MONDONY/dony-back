package com.yadony.api.matching.events;

import java.util.UUID;

public record AnnouncementDeletedEvent(UUID announcementId, UUID travelerId) {}