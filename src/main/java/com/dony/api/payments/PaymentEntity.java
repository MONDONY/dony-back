package com.dony.api.payments;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Where(clause = "deleted_at IS NULL")
public class PaymentEntity extends BaseEntity {

    @Column(name = "bid_id", unique = true)
    private UUID bidId;

    /**
     * Reference to a negotiation thread (package-request marketplace flow).
     * Mutually exclusive with bid_id — enforced by DB CHECK constraint.
     */
    @Column(name = "negotiation_thread_id", unique = true)
    private UUID negotiationThreadId;

    @Column(name = "stripe_payment_intent_id", nullable = false, unique = true, length = 255)
    private String stripePaymentIntentId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "commission_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionAmount;

    /** Montant cumulé remboursé (EUR) — miroir absolu de Stripe charge.amount_refunded. */
    @Column(name = "refunded_amount", precision = 10, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "escrow_released_at")
    private LocalDateTime escrowReleasedAt;

    @Column(name = "legacy_destination_charge", nullable = false)
    private boolean legacyDestinationCharge = false;

    @Column(name = "stripe_charge_id", length = 255)
    private String stripeChargeId;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "disputed", nullable = false)
    private boolean disputed = false;

    // V177 declares this column CHAR(3) (bpchar in Postgres, like currencies.code —
    // see CurrencyEntity). @JdbcTypeCode(SqlTypes.CHAR) matches that for Hibernate's
    // schema validator (ddl-auto: validate under the e2e profile / real Postgres).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "settlement_currency", length = 3, columnDefinition = "CHAR(3)")
    private String settlementCurrency;

    @Column(name = "settlement_amount_minor")
    private Long settlementAmountMinor;

    @Column(name = "settlement_fx_rate", precision = 18, scale = 8)
    private BigDecimal settlementFxRate;

    @Column(name = "settlement_rate_source", length = 16)
    private String settlementRateSource;

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public UUID getNegotiationThreadId() { return negotiationThreadId; }
    public void setNegotiationThreadId(UUID negotiationThreadId) { this.negotiationThreadId = negotiationThreadId; }

    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public void setStripePaymentIntentId(String stripePaymentIntentId) { this.stripePaymentIntentId = stripePaymentIntentId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }

    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }

    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }

    public LocalDateTime getEscrowReleasedAt() { return escrowReleasedAt; }
    public void setEscrowReleasedAt(LocalDateTime escrowReleasedAt) { this.escrowReleasedAt = escrowReleasedAt; }

    public boolean isLegacyDestinationCharge() { return legacyDestinationCharge; }
    public void setLegacyDestinationCharge(boolean legacyDestinationCharge) { this.legacyDestinationCharge = legacyDestinationCharge; }

    public String getStripeChargeId() { return stripeChargeId; }
    public void setStripeChargeId(String stripeChargeId) { this.stripeChargeId = stripeChargeId; }

    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }

    public boolean isDisputed() { return disputed; }
    public void setDisputed(boolean disputed) { this.disputed = disputed; }

    public String getSettlementCurrency() { return settlementCurrency; }
    public void setSettlementCurrency(String settlementCurrency) { this.settlementCurrency = settlementCurrency; }

    public Long getSettlementAmountMinor() { return settlementAmountMinor; }
    public void setSettlementAmountMinor(Long settlementAmountMinor) { this.settlementAmountMinor = settlementAmountMinor; }

    public BigDecimal getSettlementFxRate() { return settlementFxRate; }
    public void setSettlementFxRate(BigDecimal settlementFxRate) { this.settlementFxRate = settlementFxRate; }

    public String getSettlementRateSource() { return settlementRateSource; }
    public void setSettlementRateSource(String settlementRateSource) { this.settlementRateSource = settlementRateSource; }
}
