package com.dony.api.payments.cash.job;

import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.CashCommissionProperties;
import com.dony.api.payments.cash.CommissionStatus;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class OrphanedPaymentIntentCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanedPaymentIntentCleanupJob.class);

    private final BidRepository bidRepo;
    private final CashCommissionProperties props;

    public OrphanedPaymentIntentCleanupJob(BidRepository bidRepo, CashCommissionProperties props) {
        this.bidRepo = bidRepo;
        this.props = props;
    }

    @Scheduled(cron = "${dony.cash-commission.orphan-pi-cleanup-cron}", zone = "UTC")
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusMinutes(props.orphanPiTimeoutMinutes());
        bidRepo.findByCommissionStatusAndUpdatedAtBefore(CommissionStatus.REQUIRES_3DS, cutoff)
                .forEach(this::cancelOrphan);
    }

    private void cancelOrphan(BidEntity bid) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(bid.getCommissionPaymentIntentId());
            pi.cancel();
        } catch (StripeException e) {
            log.warn("Cancel PI failed for {}: {}", bid.getCommissionPaymentIntentId(), e.getMessage());
        }
        bid.setCommissionStatus(null);
        bid.setCommissionPaymentIntentId(null);
        bid.setCommissionRetryCount(bid.getCommissionRetryCount() + 1);
        bidRepo.save(bid);
    }
}
