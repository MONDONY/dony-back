package com.dony.api.automation;

import com.dony.api.matching.BidService;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutomationBidListenerTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private AutomationActionExecutor executor;
    @Mock private BidService bidService;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private NotificationDispatcher notificationDispatcher;

    private AutomationBidListener listener;
    private UUID travelerId, bidId, announcementId, senderId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new AutomationBidListener(ruleRepository, executor, bidService,
                userRepository, announcementRepository, notificationDispatcher);
        travelerId = UUID.randomUUID();
        bidId = UUID.randomUUID();
        announcementId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        when(executor.tryExecuteBidAction(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> { ((java.util.function.Supplier<?>) inv.getArgument(4)).get(); return true; });
    }

    private AutomationRuleEntity presetRule(String presetId, boolean enabled, Map<String, Object> action) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setRuleType("PRESET");
        r.setPresetRuleId(presetId);
        r.setEnabled(enabled);
        r.setAction(action);
        return r;
    }

    @Test
    void onBidCreated_rejectsOverweight_evenIfAcceptTrustedAlsoEnabled() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("4.9"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("5"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_reject_overweight", true, Map.of()),
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("10"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService).rejectBidBySystem(eq(bidId), eq(travelerId), any());
        verify(bidService, never()).acceptBidBySystem(any(), any());
    }

    @Test
    void onBidCreated_acceptsTrustedSenderWhenRatingAboveThreshold() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("4.8"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService).acceptBidBySystem(bidId, travelerId);
    }

    @Test
    void onBidCreated_doesNothingWhenNoRuleMatches() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService, never()).acceptBidBySystem(any(), any());
        verify(bidService, never()).rejectBidBySystem(any(), any(), any());
    }

    @Test
    void onBidCreated_notifiesLastMinuteWhenDepartureWithinConfiguredHours() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        announcement.setDepartureAt(OffsetDateTime.now().plusHours(10));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("alert_last_minute_bid", true, Map.of("hoursBeforeDeparture", 48))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(notificationDispatcher).notifyUser(eq(travelerId), any(), any(), any());
    }

    @Test
    void onBidCreated_doesNotNotifyLastMinuteWhenDepartureFarAway() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        announcement.setDepartureAt(OffsetDateTime.now().plusDays(10));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("alert_last_minute_bid", true, Map.of("hoursBeforeDeparture", 48))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }
}
