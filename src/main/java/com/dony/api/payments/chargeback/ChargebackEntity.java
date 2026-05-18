package com.dony.api.payments.chargeback;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chargebacks")
@org.hibernate.annotations.Where(clause = "deleted_at IS NULL")
public class ChargebackEntity extends BaseEntity {

    @Column(name = "stripe_dispute_id", nullable = false, unique = true, length = 255)
    private String stripeDisputeId;

    @Column(name = "stripe_charge_id", length = 255)
    private String stripeChargeId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "reason", length = 64)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChargebackStatus status = ChargebackStatus.OPEN;

    @Column(name = "outcome", length = 16)
    private String outcome;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public String getStripeDisputeId() { return stripeDisputeId; }
    public void setStripeDisputeId(String v) { this.stripeDisputeId = v; }
    public String getStripeChargeId() { return stripeChargeId; }
    public void setStripeChargeId(String v) { this.stripeChargeId = v; }
    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID v) { this.paymentId = v; }
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID v) { this.bidId = v; }
    public long getAmount() { return amount; }
    public void setAmount(long v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public ChargebackStatus getStatus() { return status; }
    public void setStatus(ChargebackStatus v) { this.status = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant v) { this.openedAt = v; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
}
