package com.dony.api.payments.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class WalletTopupRequest {

    @NotNull
    @DecimalMin(value = "1.00", message = "Le montant minimum est 1 €")
    @Digits(integer = 8, fraction = 2,
            message = "Le montant ne peut pas avoir plus de 2 décimales")
    private BigDecimal amount;

    @NotNull
    private String paymentMethod; // STRIPE | WAVE | ORANGE_MONEY

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
}
