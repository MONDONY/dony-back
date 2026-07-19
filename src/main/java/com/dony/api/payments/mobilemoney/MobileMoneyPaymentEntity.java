package com.dony.api.payments.mobilemoney;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "mobile_money_payments")
@Where(clause = "deleted_at IS NULL")
public class MobileMoneyPaymentEntity extends BaseEntity {

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "country_code", nullable = false, length = 5)
    private String countryCode;

    @Column(name = "phone_number", nullable = false, length = 30)
    private String phoneNumber;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "external_reference", length = 255, unique = true)
    private String externalReference;

    @Column(name = "payment_link", columnDefinition = "TEXT")
    private String paymentLink;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "webhook_received_at")
    private LocalDateTime webhookReceivedAt;

    /** Montant GELÉ en devise locale, unités mineures (règle R2 — écrit avant l'écran de confirmation). */
    @Column(name = "amount_minor")
    private Long amountMinor;

    @Column(name = "fx_rate", precision = 18, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "rate_source", length = 16)
    private String rateSource;

    /** Constaté au callback PSP. Stub actuel : = amountMinor ; PSP réel : montant rapporté. */
    @Column(name = "settled_amount_minor")
    private Long settledAmountMinor;

    // Getters / Setters
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }
    public String getPaymentLink() { return paymentLink; }
    public void setPaymentLink(String paymentLink) { this.paymentLink = paymentLink; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getWebhookReceivedAt() { return webhookReceivedAt; }
    public void setWebhookReceivedAt(LocalDateTime webhookReceivedAt) { this.webhookReceivedAt = webhookReceivedAt; }

    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }
    public BigDecimal getFxRate() { return fxRate; }
    public void setFxRate(BigDecimal fxRate) { this.fxRate = fxRate; }
    public String getRateSource() { return rateSource; }
    public void setRateSource(String rateSource) { this.rateSource = rateSource; }
    public Long getSettledAmountMinor() { return settledAmountMinor; }
    public void setSettledAmountMinor(Long settledAmountMinor) { this.settledAmountMinor = settledAmountMinor; }
}
