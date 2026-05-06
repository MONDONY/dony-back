package com.dony.api.matching;

import com.dony.api.auth.events.AccountDeletionRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionListener — tests unitaires")
class AccountDeletionListenerTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;

    @InjectMocks private AccountDeletionListener listener;

    @Test
    @DisplayName("event reçu → annonces ACTIVE/FULL annulées")
    void onDeletion_cancelsOpenAnnouncements() {
        UUID userId = UUID.randomUUID();
        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));
        verify(announcementRepository).cancelOpenAnnouncementsByUserId(userId);
    }

    @Test
    @DisplayName("event reçu → bids sender ouverts annulés")
    void onDeletion_cancelsSenderBids() {
        UUID userId = UUID.randomUUID();
        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));
        verify(bidRepository).cancelOpenSenderBidsByUserId(userId);
    }

    @Test
    @DisplayName("event reçu → bids traveler ouverts annulés")
    void onDeletion_cancelsTravelerBids() {
        UUID userId = UUID.randomUUID();
        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));
        verify(bidRepository).cancelOpenTravelerBidsByUserId(userId);
    }
}
