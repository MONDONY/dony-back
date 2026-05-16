package com.dony.api.requests.dto;

import jakarta.validation.constraints.Size;

public record NegotiationRefuseTripRequest(
    @Size(max = 280) String reason
) {}
