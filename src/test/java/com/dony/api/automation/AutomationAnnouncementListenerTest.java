package com.dony.api.automation;

import com.dony.api.matching.AnnouncementPublishedEvent;
import com.dony.api.matching.BidRepository;
import com.dony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutomationAnnouncementListenerTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private BidRepository bidRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private AutomationActionExecutor executor;

    private AutomationAnnouncementListener listener;
    private UUID travelerId, senderId1, senderId2, announcementId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new AutomationAnnouncementListener(ruleRepository, bidRepository,
                notificationDispatcher, executor);
        travelerId = UUID.randomUUID();
        senderId1 = UUID.randomUUID();
        senderId2 = UUID.randomUUID();
        announcementId = UUID.randomUUID();
    }

    private AutomationRuleEntity loyalRule(boolean enabled) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setPresetRuleId("notify_loyal_senders");
        r.setEnabled(enabled);
        r.setAction(Map.of());
        return r;
    }

    @Test
    void onAnnouncementPublished_notifiesEachLoyalSenderWhenRuleEnabled() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(true)));
        when(bidRepository.findLoyalSenderIds(travelerId, "Paris", "Dakar"))
                .thenReturn(List.of(senderId1, senderId2));

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(notificationDispatcher).notifyUser(eq(senderId1), any(), any(), any());
        verify(notificationDispatcher).notifyUser(eq(senderId2), any(), any(), any());
    }

    @Test
    void onAnnouncementPublished_doesNothingWhenRuleDisabled() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(false)));

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(bidRepository, never()).findLoyalSenderIds(any(), any(), any());
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void onAnnouncementPublished_doesNothingWhenNoLoyalSenders() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(true)));
        when(bidRepository.findLoyalSenderIds(travelerId, "Paris", "Dakar"))
                .thenReturn(List.of());

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }
}
