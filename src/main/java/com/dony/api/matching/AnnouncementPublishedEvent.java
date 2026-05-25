package com.dony.api.matching;

import java.util.UUID;

public record AnnouncementPublishedEvent(
    UUID announcementId,
    UUID travelerId,
    String travelerName,
    String departureCity,
    String arrivalCity
) {}
