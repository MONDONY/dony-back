package com.dony.api.kyc.dto;

public record KycStatusResponse(
        String kycStatus,
        String verificationStatus
) {}
