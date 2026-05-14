package com.dony.api.disputes;

import com.dony.api.disputes.events.DisputeOpenedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DisputeOpenedEventListener {

    private final DisputeService disputeService;

    public DisputeOpenedEventListener(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDisputeOpened(DisputeOpenedEvent event) {
        disputeService.openSenderNoShowDispute(
                event.getBidId(), event.getSenderId(), event.getTravelerId());
    }
}
