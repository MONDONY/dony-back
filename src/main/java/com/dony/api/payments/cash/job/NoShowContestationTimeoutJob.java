package com.dony.api.payments.cash.job;

import com.dony.api.cancellation.CancellationEntity;
import com.dony.api.cancellation.CancellationReason;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.cancellation.CancellationStatus;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Component
public class NoShowContestationTimeoutJob {

    private final CancellationRepository cancellationRepo;
    private final ApplicationEventPublisher events;

    public NoShowContestationTimeoutJob(CancellationRepository cancellationRepo,
                                        ApplicationEventPublisher events) {
        this.cancellationRepo = cancellationRepo;
        this.events = events;
    }

    @Scheduled(cron = "${dony.cash-commission.no-show-timeout-cron}", zone = "UTC")
    @Transactional
    public void run() {
        cancellationRepo.findExpiredPending(OffsetDateTime.now()).forEach(this::confirm);
    }

    private void confirm(CancellationEntity c) {
        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepo.save(c);
        events.publishEvent(new CancellationConfirmedEvent(
                c.getBidId(), c.getId(), CancellationReason.valueOf(c.getReason())));
    }
}
