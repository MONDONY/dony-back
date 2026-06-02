package com.dony.api.promo;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "promo_redemptions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"promo_code_id", "bid_id"}))
public class PromoRedemptionEntity extends BaseEntity {

    @Column(name = "promo_code_id", nullable = false)
    private UUID promoCodeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "applied_rate", nullable = false, precision = 4, scale = 3)
    private BigDecimal appliedRate;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt;

    // ── getters/setters ──────────────────────────────────────────────────────

    public UUID getPromoCodeId() { return promoCodeId; }
    public void setPromoCodeId(UUID promoCodeId) { this.promoCodeId = promoCodeId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public BigDecimal getAppliedRate() { return appliedRate; }
    public void setAppliedRate(BigDecimal appliedRate) { this.appliedRate = appliedRate; }

    public LocalDateTime getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(LocalDateTime redeemedAt) { this.redeemedAt = redeemedAt; }
}
