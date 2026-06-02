package com.dony.api.promo;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "promo_codes")
@Where(clause = "deleted_at IS NULL")
public class PromoCodeEntity extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    /** Taux de commission appliqué quand le promo est utilisé (ex. 0.060 = 6 %). */
    @Column(name = "rate", nullable = false, precision = 4, scale = 3)
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, length = 10)
    private PromoCodeTarget target = PromoCodeTarget.ANY;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_to")
    private LocalDateTime validTo;

    /** null = illimité. */
    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit = 1;

    @Column(name = "redeemed_count", nullable = false)
    private int redeemedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PromoCodeStatus status = PromoCodeStatus.ACTIVE;

    // ── getters/setters ──────────────────────────────────────────────────────

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code != null ? code.toUpperCase() : null; }

    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }

    public PromoCodeTarget getTarget() { return target; }
    public void setTarget(PromoCodeTarget target) { this.target = target; }

    public LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }

    public LocalDateTime getValidTo() { return validTo; }
    public void setValidTo(LocalDateTime validTo) { this.validTo = validTo; }

    public Integer getMaxRedemptions() { return maxRedemptions; }
    public void setMaxRedemptions(Integer maxRedemptions) { this.maxRedemptions = maxRedemptions; }

    public int getPerUserLimit() { return perUserLimit; }
    public void setPerUserLimit(int perUserLimit) { this.perUserLimit = perUserLimit; }

    public int getRedeemedCount() { return redeemedCount; }
    public void setRedeemedCount(int redeemedCount) { this.redeemedCount = redeemedCount; }

    public PromoCodeStatus getStatus() { return status; }
    public void setStatus(PromoCodeStatus status) { this.status = status; }
}
