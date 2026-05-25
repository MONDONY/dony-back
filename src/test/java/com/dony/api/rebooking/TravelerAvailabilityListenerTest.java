package com.dony.api.rebooking;

import com.dony.api.matching.AnnouncementPublishedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelerAvailabilityListenerTest {

    @Mock TravelerSubscriptionRepository subscriptionRepository;
    @Mock NotificationDispatcher notificationDispatcher;
    @InjectMocks TravelerAvailabilityListener listener;

    @Test
    void onAnnouncementPublished_notifiesAllSubscribedSenders() {
        UUID travelerId = UUID.randomUUID();
        UUID sender1    = UUID.randomUUID();
        UUID sender2    = UUID.randomUUID();

        when(subscriptionRepository.findSenderIdsByTravelerId(travelerId))
            .thenReturn(List.of(sender1, sender2));

        AnnouncementPublishedEvent event = new AnnouncementPublishedEvent(
            UUID.randomUUID(), travelerId, "Fatoumata Koné", "Paris", "Bamako"
        );

        listener.onAnnouncementPublished(event);

        verify(notificationDispatcher).notifyUser(
            eq(sender1), contains("Fatoumata Koné"), any(), any(Map.class)
        );
        verify(notificationDispatcher).notifyUser(
            eq(sender2), contains("Fatoumata Koné"), any(), any(Map.class)
        );
    }

    @Test
    void onAnnouncementPublished_noSubscribers_sendsNoNotifications() {
        UUID travelerId = UUID.randomUUID();
        when(subscriptionRepository.findSenderIdsByTravelerId(travelerId))
            .thenReturn(List.of());

        AnnouncementPublishedEvent event = new AnnouncementPublishedEvent(
            UUID.randomUUID(), travelerId, "Solo Coulibaly", "Paris", "Dakar"
        );

        listener.onAnnouncementPublished(event);

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }
}
