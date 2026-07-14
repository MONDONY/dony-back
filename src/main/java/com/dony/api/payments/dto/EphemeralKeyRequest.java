package com.dony.api.payments.dto;

import jakarta.validation.constraints.NotBlank;

/** Corps du POST /payments/me/ephemeral-key — version API Stripe demandée par le SDK flutter_stripe. */
public record EphemeralKeyRequest(@NotBlank String stripeVersion) {
}
