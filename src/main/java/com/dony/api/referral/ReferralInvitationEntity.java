package com.dony.api.referral;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks referral invitations: from a referrer to a referee.
 * Status lifecycle: PENDING → SIGNED_UP → FIRST_DELIVERY → REWARDED
 */
@Entity
@Table(name = "referral_invitations")
@Where(clause = "deleted_at IS NULL")
public class ReferralInvitationEntity extends BaseEntity {

    @Column(name = "referrer_user_id", nullable = false)
    private UUID referrerUserId;

    @Column(name = "referee_user_id")
    private UUID refereeUserId;

    @Column(name = "referee_phone", length = 20)
    private String refereePhone;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "code_used", length = 20)
    private String codeUsed;

    @Column(name = "rewarded_at")
    private LocalDateTime rewardedAt;

    @Column(name = "credit_amount_cents")
    private Integer creditAmountCents;

    public UUID getReferrerUserId() { return referrerUserId; }
    public void setReferrerUserId(UUID referrerUserId) { this.referrerUserId = referrerUserId; }

    public UUID getRefereeUserId() { return refereeUserId; }
    public void setRefereeUserId(UUID refereeUserId) { this.refereeUserId = refereeUserId; }

    public String getRefereePhone() { return refereePhone; }
    public void setRefereePhone(String refereePhone) { this.refereePhone = refereePhone; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCodeUsed() { return codeUsed; }
    public void setCodeUsed(String codeUsed) { this.codeUsed = codeUsed; }

    public LocalDateTime getRewardedAt() { return rewardedAt; }
    public void setRewardedAt(LocalDateTime rewardedAt) { this.rewardedAt = rewardedAt; }

    public Integer getCreditAmountCents() { return creditAmountCents; }
    public void setCreditAmountCents(Integer creditAmountCents) { this.creditAmountCents = creditAmountCents; }
}
