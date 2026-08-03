package com.yadony.api.matching;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le user supprimé est ici le SENDER (bids passés sur des annonces d'AUTRES voyageurs) :
 * chaque bid ouvert est annulé individuellement via {@link BidService#cancelBidForDeletedSender}
 * (refund + notif via BidRejectedEvent), sans toucher à l'annonce d'un tiers.
 * Le cas voyageur (ses propres annonces) est géré séparément par
 * {@code cancellation.AccountDeletionCancellationListener}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionListener — tests unitaires")
class AccountDeletionListenerTest {

    @Mock private BidRepository bidRepository;
    @Mock private BidService bidService;

    @InjectMocks private AccountDeletionListener listener;

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("event reçu → annule chaque bid ouvert où le user supprimé est sender")
    void onDeletion_cancelsEachOpenSenderBid() {
        UUID userId = UUID.randomUUID();
        UUID bidId1 = UUID.randomUUID();
        UUID bidId2 = UUID.randomUUID();
        BidEntity b1 = new BidEntity();
        setId(b1, bidId1);
        BidEntity b2 = new BidEntity();
        setId(b2, bidId2);

        when(bidRepository.findBySenderIdAndStatusIn(userId, BidService.CANCELLABLE_BID_STATUSES)).thenReturn(List.of(b1, b2));

        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));

        verify(bidService).cancelBidForDeletedSender(bidId1);
        verify(bidService).cancelBidForDeletedSender(bidId2);
    }

    @Test
    @DisplayName("aucun bid ouvert → aucun appel à cancelBidForDeletedSender")
    void onDeletion_noOpenBids_noCalls() {
        UUID userId = UUID.randomUUID();
        when(bidRepository.findBySenderIdAndStatusIn(userId, BidService.CANCELLABLE_BID_STATUSES)).thenReturn(List.of());

        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));

        verify(bidService, never()).cancelBidForDeletedSender(any());
    }
}
