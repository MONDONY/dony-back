package com.dony.api.requests.service;

import com.dony.api.matching.events.BidMaterializedEvent;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Stamps {@code materialized_bid_id} onto a NegotiationThread once matching/
 * has materialised the backing bid. Lets the mobile app open the bid detail
 * (tracking, no-show…) directly from the thread.
 *
 * Cross-package contract: matching/ publishes {@link BidMaterializedEvent},
 * requests/ listens here — no direct service injection between packages.
 *
 * Runs {@code AFTER_COMMIT} in {@code REQUIRES_NEW} (project rule for
 * event listeners) so it never reads uncommitted state and stamping the
 * thread is isolated from the publishing transaction.
 */
@Component
public class BidMaterializedListener {

    private static final Logger log = LoggerFactory.getLogger(BidMaterializedListener.class);

    private final NegotiationThreadRepository threadRepository;

    public BidMaterializedListener(NegotiationThreadRepository threadRepository) {
        this.threadRepository = threadRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidMaterialized(BidMaterializedEvent event) {
        threadRepository.findById(event.getNegotiationThreadId()).ifPresent(thread -> {
            thread.setMaterializedBidId(event.getBidId());
            threadRepository.save(thread);
            log.debug("Thread {} → materializedBidId {}",
                    event.getNegotiationThreadId(), event.getBidId());
        });
    }
}
