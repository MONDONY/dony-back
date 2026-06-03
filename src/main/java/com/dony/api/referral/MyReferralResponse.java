package com.dony.api.referral;

/**
 * Response DTO for GET /me/referral.
 */
public record MyReferralResponse(
        String code,
        String shareUrl,
        int totalInvited,
        int signedUp,
        int rewarded,
        int totalEarnedCents,
        boolean hasBeenReferred
) {}
