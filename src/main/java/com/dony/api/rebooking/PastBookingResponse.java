package com.dony.api.rebooking;

import java.time.LocalDate;
import java.util.UUID;

public record PastBookingResponse(
    UUID bidId,
    UUID travelerId,
    String travelerName,
    String travelerBadge,      // "PRO" | null
    String departureCity,
    String arrivalCity,
    LocalDate lastTripDate,
    long completedTripsWithThisTraveler
) {}
