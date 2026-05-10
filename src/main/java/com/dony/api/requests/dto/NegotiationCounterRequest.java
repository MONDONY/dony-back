package com.dony.api.requests.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record NegotiationCounterRequest(
    @NotNull @DecimalMin("0.01") @DecimalMax("500.0") BigDecimal proposedPriceEur,
    @Size(max = 280) String body
) {}
