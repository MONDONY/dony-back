package com.dony.api.matching;

import com.dony.api.cancellation.events.TravelerNoShowReportedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Écoute le signalement manuel d'un voyageur absent par l'expéditeur
 * ({@link TravelerNoShowReportedEvent}, publié par cancellation/) et délègue au
 * {@link NoShowService} pour marquer le bid NO_SHOW. Communication cross-package
 * via event uniquement (jamais d'injection directe matching/ ↔ cancellation/).
 */
@Component
public class TravelerNoShowReportListener {

    private final NoShowService noShowService;

    public TravelerNoShowReportListener(NoShowService noShowService) {
        this.noShowService = noShowService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTravelerNoShowReported(TravelerNoShowReportedEvent event) {
        noShowService.recordTravelerNoShow(event.getBidId(), "sender_report");
    }
}
