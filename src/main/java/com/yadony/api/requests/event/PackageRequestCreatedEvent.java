package com.yadony.api.requests.event;

import java.time.LocalDate;
import java.util.UUID;

public record PackageRequestCreatedEvent(
    UUID requestId, UUID senderId,
    String departureCity, String arrivalCity, LocalDate desiredDate
) {}
