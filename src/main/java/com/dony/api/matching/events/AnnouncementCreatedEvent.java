package com.dony.api.matching.events;

import java.util.UUID;

public record AnnouncementCreatedEvent(
    UUID announcementId,
    String departureCity,
    String departureCountry,
    String arrivalCity,
    String arrivalCountry
) {}
