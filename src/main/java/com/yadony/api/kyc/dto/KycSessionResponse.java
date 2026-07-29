package com.yadony.api.kyc.dto;

public record KycSessionResponse(
        String stripeUrl,
        String sessionId,
        String status
) {}
