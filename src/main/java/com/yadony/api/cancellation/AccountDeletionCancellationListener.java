package com.yadony.api.cancellation;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Le user supprimé est ici le VOYAGEUR : ses annonces ACTIVE/FULL sont annulées via
 * {@link CancellationService#cancelAnnouncementForDeletedTraveler} — même cœur que
 * {@code cancelTrip} (bids annulés + CancellationEntity + rematch + TripCancelledEvent),
 * ce qui déclenche refund/notification/rematch pour chaque sender affecté. Vit dans
 * cancellation/ (pas matching/) car c'est de la logique de cancellation, cf. CLAUDE.md.
 * Le cas expéditeur (ses bids sur des annonces de tiers) est géré séparément par
 * {@code matching.AccountDeletionListener}.
 */
@Component
public class AccountDeletionCancellationListener {

    private final AnnouncementRepository announcementRepository;
    private final CancellationService cancellationService;

    public AccountDeletionCancellationListener(AnnouncementRepository announcementRepository,
                                               CancellationService cancellationService) {
        this.announcementRepository = announcementRepository;
        this.cancellationService = cancellationService;
    }

    // AFTER_COMMIT + REQUIRES_NEW : les publishers (UserService#requestDeletion,
    // AccountFinalizationService#finalize) sont eux-mêmes @Transactional — attendre leur
    // commit évite d'annuler des annonces pour une suppression qui pourrait encore rollback.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeletionRequested(AccountDeletionRequestedEvent event) {
        for (AnnouncementEntity announcement : announcementRepository.findActiveByTravelerId(event.getUserId())) {
            cancellationService.cancelAnnouncementForDeletedTraveler(announcement.getId());
        }
    }
}
