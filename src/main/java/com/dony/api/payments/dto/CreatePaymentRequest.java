package com.dony.api.payments.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class CreatePaymentRequest {

    @NotNull(message = "bidId est obligatoire")
    private UUID bidId;

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
}
