package com.yadony.api.referral;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /referral/redeem.
 */
public record RedeemCodeRequest(@NotBlank String code) {}
