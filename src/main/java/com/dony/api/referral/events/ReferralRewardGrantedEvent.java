package com.dony.api.referral.events;

import java.util.UUID;

/**
 * Published by {@code DeliveryConfirmedReferralListener} when a referral reward is granted
 * to a referrer (parrain), after the referee's first completed delivery.
 *
 * <p>The {@code payments/wallet} package listens to this event to credit the referrer's
 * spendable wallet balance. Cross-package communication is event-driven only (CLAUDE.md rule #5);
 * the referral package never injects {@code WalletService} directly.
 *
 * @param referrerUserId the parrain who earns the reward
 * @param amountCents     reward amount in cents (e.g. 500 = 5€)
 * @param invitationId    the referral invitation id, used to build an idempotency key
 */
public record ReferralRewardGrantedEvent(UUID referrerUserId, int amountCents, UUID invitationId) {
}
