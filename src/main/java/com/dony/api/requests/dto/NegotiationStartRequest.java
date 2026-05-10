package com.dony.api.requests.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record NegotiationStartRequest(
    @NotNull UUID packageRequestId,
    @NotNull @DecimalMin("0.01") @DecimalMax("500.0") BigDecimal proposedPriceEur,
    @NotNull LocalDate travelerTravelDate,
    @NotNull @DecimalMin("0.01") BigDecimal travelerAvailableKg,
    UUID travelerAnnouncementId,
    @Size(max = 280) String body
) {}
