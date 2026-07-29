package com.yadony.api.requests.dto;

import jakarta.validation.constraints.Size;

public record NegotiationAcceptRequest(
    @Size(max = 280) String body
) {}
