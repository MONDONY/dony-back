package com.dony.api.payments;

public enum PaymentStatus {
    PENDING,   // PaymentIntent created, awaiting customer card confirmation
    ESCROW,    // Card authorized (requires_capture) — funds held
    RELEASED,  // Captured after delivery confirmation
    FAILED,    // Card authorization failed
    REFUNDED   // Full refund issued
}
