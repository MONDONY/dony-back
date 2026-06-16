package com.dony.api.auth;

import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelerStatsListenerTest {

    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;
    @Mock private CacheManager cacheManager;
    @Mock private Cache tripsSummaryCache;

    private TravelerStatsListener listener;

    @BeforeEach
    void setUp() {
        listener = new TravelerStatsListener(userRepository, bidRepository,
                announcementRepository, auditService, cacheManager);
        lenient().when(cacheManager.getCache("trips-summary")).thenReturn(tripsSummaryCache);
    }

    private static void setEntityId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private BidEntity completedBid(UUID bidId, UUID announcementId) {
        BidEntity bid = new BidEntity();
        setEntityId(bid, bidId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.COMPLETED);
        return bid;
    }

    private AnnouncementEntity announcement(UUID id, boolean alreadyCounted) {
        AnnouncementEntity a = new AnnouncementEntity();
        setEntityId(a, id);
        a.setTotalTripsCounted(alreadyCounted);
        return a;
    }

    private UserEntity traveler(UUID travelerId, int currentTotal) {
        UserEntity u = new UserEntity();
        setEntityId(u, travelerId);
        u.setTotalTrips(currentTotal);
        return u;
    }

    @Test
    void increments_totalTrips_on_first_bid_completed_for_announcement() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, false);
        UserEntity user = traveler(travelerId, 4);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(user));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        assertThat(user.getTotalTrips()).isEqualTo(5);
        assertThat(ann.isTotalTripsCounted()).isTrue();
        verify(userRepository).save(user);
        verify(announcementRepository).save(ann);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(travelerId), eq("TOTAL_TRIPS_INCREMENTED"),
                eq(travelerId), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("bidId", bidId.toString())
                .containsEntry("announcementId", announcementId.toString())
                .containsEntry("newTotal", "5");
    }

    @Test
    void is_idempotent_when_announcement_already_counted() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, true);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void flags_announcement_as_counted_after_increment() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, false);
        UserEntity user = traveler(travelerId, 0);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(user));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        assertThat(ann.isTotalTripsCounted()).isTrue();
        assertThat(user.getTotalTrips()).isEqualTo(1);
    }

    @Test
    void noop_when_bid_not_found() {
        UUID bidId = UUID.randomUUID();
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        verify(announcementRepository, never()).findById(any());
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void noop_when_announcement_not_found() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void traveler_not_found_marks_announcement_counted_and_logs_error() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, false);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        assertThat(ann.isTotalTripsCounted()).isTrue();
        verify(announcementRepository).save(ann);
        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void skips_increment_if_bid_status_not_completed() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        bid.setStatus(BidStatus.ACCEPTED);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(announcementRepository, never()).findById(any());
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void evicts_trips_summary_cache_after_increment() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, false);
        UserEntity user = traveler(travelerId, 2);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(user));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(tripsSummaryCache).evict(travelerId);
    }

    @Test
    void does_not_evict_cache_when_already_counted() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, true);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(tripsSummaryCache, never()).evict(any());
    }
}
