package com.dony.api.kyc.dto;

public record KycSessionResponse(
        String stripeUrl,
        String sessionId,
        String status
) {}
