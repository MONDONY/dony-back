package com.yadony.api.matching;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Le user supprimé est ici le SENDER : chaque bid ouvert qu'il a passé sur l'annonce
 * d'un AUTRE voyageur est annulé individuellement via {@link BidService#cancelBidForDeletedSender}
 * (refund + notification via BidRejectedEvent), sans toucher à l'annonce du tiers.
 * Le cas voyageur (ses propres annonces) est géré séparément par
 * {@code cancellation.AccountDeletionCancellationListener}.
 */
@Component
public class AccountDeletionListener {

    private final BidRepository bidRepository;
    private final BidService bidService;

    public AccountDeletionListener(BidRepository bidRepository, BidService bidService) {
        this.bidRepository = bidRepository;
        this.bidService = bidService;
    }

    // AFTER_COMMIT + REQUIRES_NEW : les publishers (UserService#requestDeletion,
    // AccountFinalizationService#finalize) sont eux-mêmes @Transactional — attendre leur
    // commit évite d'annuler des bids pour une suppression qui pourrait encore rollback.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeletionRequested(AccountDeletionRequestedEvent event) {
        for (BidEntity bid : bidRepository.findBySenderIdAndStatusIn(
                event.getUserId(), BidService.CANCELLABLE_BID_STATUSES)) {
            bidService.cancelBidForDeletedSender(bid.getId());
        }
    }
}
