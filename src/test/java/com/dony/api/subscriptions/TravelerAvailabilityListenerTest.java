package com.dony.api.subscriptions;

import com.dony.api.matching.AnnouncementPublishedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelerAvailabilityListenerTest {

    @Mock TravelerSubscriptionRepository repo;
    @Mock NotificationDispatcher dispatcher;
    @InjectMocks TravelerAvailabilityListener listener;

    AnnouncementPublishedEvent event(UUID travelerId) {
        return new AnnouncementPublishedEvent(UUID.randomUUID(), travelerId, "Ibrahima D", "Paris", "Dakar");
    }

    @Test
    void notifies_inAppAlways_pushOnlyIfEnabled() {
        UUID travelerId = UUID.randomUUID();
        TravelerSubscriptionEntity pushOff = new TravelerSubscriptionEntity();
        pushOff.setSenderId(UUID.randomUUID()); pushOff.setTravelerId(travelerId); pushOff.setPushEnabled(false);
        TravelerSubscriptionEntity pushOn = new TravelerSubscriptionEntity();
        pushOn.setSenderId(UUID.randomUUID()); pushOn.setTravelerId(travelerId); pushOn.setPushEnabled(true);
        when(repo.findAllByTravelerId(travelerId)).thenReturn(List.of(pushOff, pushOn));

        listener.onAnnouncementPublished(event(travelerId));

        verify(repo, times(2)).save(any(TravelerSubscriptionEntity.class));
        verify(dispatcher).notifyUser(eq(pushOff.getSenderId()), anyString(), anyString(), anyMap(), eq(false));
        verify(dispatcher).notifyUser(eq(pushOn.getSenderId()), anyString(), anyString(), anyMap(), eq(true));
    }

    @Test
    void noSubscribers_doesNothing() {
        UUID travelerId = UUID.randomUUID();
        when(repo.findAllByTravelerId(travelerId)).thenReturn(List.of());
        listener.onAnnouncementPublished(event(travelerId));
        verifyNoInteractions(dispatcher);
    }
}
