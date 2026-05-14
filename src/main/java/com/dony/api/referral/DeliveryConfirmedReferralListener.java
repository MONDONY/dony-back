package com.dony.api.referral;

import com.dony.api.common.AuditService;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public DeliveryConfirmedReferralListener(ReferralInvitationRepository referralInvitationRepository,
                                              UserCreditRepository userCreditRepository,
                                              BidRepository bidRepository,
                                              AuditService auditService,
                                              ReferralConfig config) {
        this.referralInvitationRepository = referralInvitationRepository;
        this.userCreditRepository = userCreditRepository;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
        this.config = config;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        UUID senderId = event.getSenderId();

        Optional<ReferralInvitationEntity> invOpt =
                referralInvitationRepository.findByRefereeUserIdAndStatus(senderId, "SIGNED_UP");

        if (invOpt.isEmpty()) {
            log.debug("No SIGNED_UP referral invitation for sender {}, skipping reward", senderId);
            return;
        }

        // Only reward on the first completed delivery
        long completedCount = bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, senderId);
        if (completedCount != 1) {
            log.debug("Sender {} has {} completed bids — not the first, skipping referral reward",
                    senderId, completedCount);
            return;
        }

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
    }
}
