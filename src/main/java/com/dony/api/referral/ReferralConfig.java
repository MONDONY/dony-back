package com.dony.api.referral;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Business configuration for the referral system.
 * All limits are externalized in application.yml under {@code dony.referral}.
 */
@ConfigurationProperties(prefix = "dony.referral")
@Configuration
public class ReferralConfig {

    /** Amount credited to the referrer on the referee's first delivery (in euro cents). */
    private int rewardAmountCents = 500;

    /** Maximum number of invitations a user may send. */
    private int maxInvitationsPerUser = 50;

    /** Minimum days between two consecutive code regenerations. */
    private int codeRegenerationCooldownDays = 30;

    public int getRewardAmountCents() { return rewardAmountCents; }
    public void setRewardAmountCents(int rewardAmountCents) { this.rewardAmountCents = rewardAmountCents; }

    public int getMaxInvitationsPerUser() { return maxInvitationsPerUser; }
    public void setMaxInvitationsPerUser(int maxInvitationsPerUser) { this.maxInvitationsPerUser = maxInvitationsPerUser; }

    public int getCodeRegenerationCooldownDays() { return codeRegenerationCooldownDays; }
    public void setCodeRegenerationCooldownDays(int codeRegenerationCooldownDays) {
        this.codeRegenerationCooldownDays = codeRegenerationCooldownDays;
    }
}
