package com.dony.api.payments.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public class CreatePaymentRequest {

    @NotNull(message = "bidId est obligatoire")
    private UUID bidId;

    private BigDecimal totalNetEur;  // si fourni, formule NET×(1+taux dony.commission.rate); sinon ancien calcul GROSS

    // null/true → setup_future_usage=off_session (carte réutilisable) ; false → non enregistrée
    private Boolean savePaymentMethod;

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public BigDecimal getTotalNetEur() { return totalNetEur; }
    public void setTotalNetEur(BigDecimal totalNetEur) { this.totalNetEur = totalNetEur; }
    public Boolean getSavePaymentMethod() { return savePaymentMethod; }
    public void setSavePaymentMethod(Boolean savePaymentMethod) { this.savePaymentMethod = savePaymentMethod; }
}
