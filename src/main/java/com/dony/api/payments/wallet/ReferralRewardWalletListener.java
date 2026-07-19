package com.dony.api.payments.wallet;

import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.common.money.MinorUnits;
import com.dony.api.referral.events.ReferralRewardGrantedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;

/**
 * Credits a referrer's spendable wallet balance when a referral reward is granted.
 *
 * <p>Listens to {@link ReferralRewardGrantedEvent} published by the referral package
 * (cross-package communication is event-driven only — CLAUDE.md rule #5). Uses
 * {@code AFTER_COMMIT} + a new transaction so the credit lands only once the reward
 * grant has committed (CLAUDE.md rule #18).
 *
 * <p>Idempotency key {@code referral-reward-{invitationId}} guarantees the parrain is
 * credited exactly once per invitation, even if the event is replayed.
 */
@Component
public class ReferralRewardWalletListener {

    private static final Logger log = LoggerFactory.getLogger(ReferralRewardWalletListener.class);

    private final WalletService walletService;
    private final CurrencyRegistry currencyRegistry;

    public ReferralRewardWalletListener(WalletService walletService, CurrencyRegistry currencyRegistry) {
        this.walletService = walletService;
        this.currencyRegistry = currencyRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReferralRewardGranted(ReferralRewardGrantedEvent event) {
        // user_credits stores cents; the wallet stores euros as DECIMAL(10,2).
        BigDecimal amountEur = MinorUnits
                .fromMinor(event.amountCents(), "EUR", currencyRegistry).amount();
        String idempotencyKey = "referral-reward-" + event.invitationId();

        walletService.credit(
                event.referrerUserId(),
                amountEur,
                WalletTransactionType.REFERRAL_REWARD,
                event.invitationId().toString(),
                idempotencyKey);

        log.info("Referral reward credited to wallet: referrer={} amountEur={} invitation={}",
                event.referrerUserId(), amountEur, event.invitationId());
    }
}
