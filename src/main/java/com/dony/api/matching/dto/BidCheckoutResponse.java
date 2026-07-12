package com.dony.api.matching.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record BidCheckoutResponse(
        UUID bidId,
        String clientSecret,
        String publishableKey,
        LocalDateTime expiresAt,
        // Types du PaymentIntent (ex. ["card","paypal"]) — bouton PayPal conditionnel
        // dans la DonyPaymentSheet (le SDK flutter_stripe ne les expose pas).
        List<String> paymentMethodTypes
) {
    /** Constructeur historique (sans paymentMethodTypes). */
    public BidCheckoutResponse(UUID bidId, String clientSecret, String publishableKey,
                               LocalDateTime expiresAt) {
        this(bidId, clientSecret, publishableKey, expiresAt, null);
    }
}
