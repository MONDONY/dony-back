package com.dony.api.payments.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public class CreatePaymentRequest {

    @NotNull(message = "bidId est obligatoire")
    private UUID bidId;

    private BigDecimal totalNetEur;  // si fourni, nouvelle formule NET×1.12; sinon ancien calcul GROSS

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public BigDecimal getTotalNetEur() { return totalNetEur; }
    public void setTotalNetEur(BigDecimal totalNetEur) { this.totalNetEur = totalNetEur; }
}
