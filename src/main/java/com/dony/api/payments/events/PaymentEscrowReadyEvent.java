package com.dony.api.payments.events;

import java.util.UUID;

public class PaymentEscrowReadyEvent {

    private final UUID bidId;
    private final UUID paymentId;

    public PaymentEscrowReadyEvent(UUID bidId, UUID paymentId) {
        this.bidId = bidId;
        this.paymentId = paymentId;
    }

    public UUID getBidId() { return bidId; }
    public UUID getPaymentId() { return paymentId; }
}
