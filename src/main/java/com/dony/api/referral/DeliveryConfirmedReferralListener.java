package com.dony.api.referral;

import com.dony.api.common.AuditService;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.referral.events.ReferralRewardGrantedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Listens for {@link DeliveryConfirmedEvent} after the tracking transaction commits,
 * then rewards the referrer if this is the referee's first completed delivery.
 *
 * <p>Uses {@code @TransactionalEventListener(phase = AFTER_COMMIT)} + a new transaction
 * so we read data that is guaranteed to be committed (CLAUDE.md rule #18).
 */
@Component
public class DeliveryConfirmedReferralListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConfirmedReferralListener.class);

    private final ReferralInvitationRepository referralInvitationRepository;
    private final UserCreditRepository userCreditRepository;
    private final BidRepository bidRepository;
    private final AuditService auditService;
    private final ReferralConfig config;
    private final ApplicationEventPublisher eventPublisher;

    public DeliveryConfirmedReferralListener(ReferralInvitationRepository referralInvitationRepository,
                                              UserCreditRepository userCreditRepository,
                                              BidRepository bidRepository,
                                              AuditService auditService,
                                              ReferralConfig config,
                                              ApplicationEventPublisher eventPublisher) {
        this.referralInvitationRepository = referralInvitationRepository;
        this.userCreditRepository = userCreditRepository;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
        this.config = config;
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        try {
            doReward(event);
        } catch (Exception ex) {
            // REQUIRES_NEW rolls back silently; log explicitly so ops can investigate
            log.error("Referral reward failed for bid={} sender={}: {}",
                    event.getBidId(), event.getSenderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    private void doReward(DeliveryConfirmedEvent event) {
        UUID senderId = event.getSenderId();

        Optional<ReferralInvitationEntity> invOpt =
                referralInvitationRepository.findByRefereeUserIdAndStatus(senderId, "SIGNED_UP");

        if (invOpt.isEmpty()) {
            log.debug("No SIGNED_UP referral invitation for sender {}, skipping reward", senderId);
            return;
        }

        long completedCount = bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, senderId);

        if (completedCount == 0) {
            // Should not happen — bid was just committed as COMPLETED. Log a warning so
            // this edge case is visible in monitoring rather than silently skipped.
            log.warn("Referral reward: completed-bid count is 0 for sender {} bid {} — " +
                     "possible Hibernate timing issue; skipping to avoid duplicate reward on retry",
                    senderId, event.getBidId());
            return;
        }

        if (completedCount > 1) {
            log.debug("Sender {} has {} completed bids — not the first, skipping referral reward",
                    senderId, completedCount);
            return;
        }

        // completedCount == 1 → this IS the first completed delivery
        ReferralInvitationEntity inv = invOpt.get();
        inv.setStatus("REWARDED");
        inv.setRewardedAt(LocalDateTime.now(ZoneOffset.UTC));
        inv.setCreditAmountCents(config.getRewardAmountCents());
        referralInvitationRepository.save(inv);

        UserCreditEntity credit = new UserCreditEntity();
        credit.setUserId(inv.getReferrerUserId());
        credit.setAmountCents(config.getRewardAmountCents());
        credit.setSource("REFERRAL_REWARD");
        credit.setReferenceId(inv.getId());
        userCreditRepository.save(credit);

        auditService.log(
                "REFERRAL_INVITATION",
                inv.getId(),
                "REFERRAL_REWARDED",
                inv.getReferrerUserId(),
                Map.of(
                        "referrerId", inv.getReferrerUserId().toString(),
                        "refereeId", senderId.toString(),
                        "amountCents", config.getRewardAmountCents(),
                        "bidId", event.getBidId().toString()
                )
        );

        log.info("Referral reward granted: referrer={} referee={} amountCents={}",
                inv.getReferrerUserId(), senderId, config.getRewardAmountCents());

        // Credit the referrer's spendable wallet via an event (cross-package = events only).
        // Published inside this REQUIRES_NEW transaction → the wallet listener fires AFTER_COMMIT.
        eventPublisher.publishEvent(new ReferralRewardGrantedEvent(
                inv.getReferrerUserId(), config.getRewardAmountCents(), inv.getId()));
    }
}
