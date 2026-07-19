package com.dony.api.payments.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public class WalletTopupRequest {

    @NotNull
    @DecimalMin(value = "1.00", message = "Le montant minimum est 1 €")
    @Digits(integer = 8, fraction = 2,
            message = "Le montant ne peut pas avoir plus de 2 décimales")
    private BigDecimal amount;

    @NotNull
    private String paymentMethod; // STRIPE | WAVE | ORANGE_MONEY | MTN_MONEY

    // Requis seulement si paymentMethod != STRIPE — vérifié dans WalletTopupOrchestrator,
    // pas ici (Stripe n'a besoin d'aucun des deux).
    @Pattern(regexp = "^[A-Z]{2}$", message = "Code pays invalide (format ISO2, ex. SN)")
    private String countryCode;

    @Pattern(regexp = "^\\+?[1-9]\\d{6,19}$", message = "Numéro de téléphone invalide")
    private String phoneNumber;

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
}
