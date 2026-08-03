package com.yadony.api.cancellation;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
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
 * Le user supprimé est ici le VOYAGEUR : ses annonces ACTIVE/FULL sont annulées via
 * {@link CancellationService#cancelAnnouncementForDeletedTraveler} — même cœur que
 * {@code cancelTrip} (bids annulés + CancellationEntity + rematch + TripCancelledEvent),
 * ce qui déclenche refund/notif/rematch pour les senders affectés. Vit dans cancellation/
 * (pas matching/) car c'est de la logique de cancellation, cf. CLAUDE.md.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionCancellationListener — tests unitaires")
class AccountDeletionCancellationListenerTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private CancellationService cancellationService;

    @InjectMocks private AccountDeletionCancellationListener listener;

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
    @DisplayName("event reçu → annule chaque annonce ACTIVE/FULL du voyageur supprimé")
    void onDeletion_cancelsEachOpenAnnouncement() {
        UUID userId = UUID.randomUUID();
        UUID announcementId1 = UUID.randomUUID();
        UUID announcementId2 = UUID.randomUUID();
        AnnouncementEntity a1 = new AnnouncementEntity();
        setId(a1, announcementId1);
        AnnouncementEntity a2 = new AnnouncementEntity();
        setId(a2, announcementId2);

        when(announcementRepository.findActiveByTravelerId(userId)).thenReturn(List.of(a1, a2));

        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));

        verify(cancellationService).cancelAnnouncementForDeletedTraveler(announcementId1);
        verify(cancellationService).cancelAnnouncementForDeletedTraveler(announcementId2);
    }

    @Test
    @DisplayName("aucune annonce ouverte → aucun appel")
    void onDeletion_noOpenAnnouncements_noCalls() {
        UUID userId = UUID.randomUUID();
        when(announcementRepository.findActiveByTravelerId(userId)).thenReturn(List.of());

        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));

        verify(cancellationService, never()).cancelAnnouncementForDeletedTraveler(any());
    }
}
