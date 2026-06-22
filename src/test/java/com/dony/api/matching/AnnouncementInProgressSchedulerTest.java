package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.config.DonyConfigProperties;
import com.dony.api.matching.events.AnnouncementInProgressEvent;
import com.dony.api.matching.events.BidExpiredOnDepartureEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementInProgressScheduler (safety net)")
class AnnouncementInProgressSchedulerTest {

    @Mock private AnnouncementService announcementService;

    private AnnouncementInProgressScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AnnouncementInProgressScheduler(announcementService);
    }

    @Test
    @DisplayName("délègue au service lors du déclenchement horaire")
    void schedulerDelegatesToService() {
        scheduler.processInProgressTransitions();
        verify(announcementService, times(1)).triggerInProgressTransitions();
    }
}

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService — triggerInProgressTransitions")
class AnnouncementInProgressTransitionTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private org.springframework.cache.CacheManager cacheManager;

    private AnnouncementService service;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        DonyConfigProperties.Limits.NonPro nonPro = new DonyConfigProperties.Limits.NonPro(2);
        DonyConfigProperties.Limits limits = new DonyConfigProperties.Limits(nonPro);
        DonyConfigProperties config = new DonyConfigProperties(
                new DonyConfigProperties.Commission(new java.math.BigDecimal("0.12")), limits, null);
        service = new AnnouncementService(
                announcementRepository, bidRepository,
                mock(com.dony.api.auth.UserRepository.class),
                auditService, eventPublisher, config,
                mock(PriceGridService.class),
                mock(com.dony.api.country.FlagService.class),
                mock(com.dony.api.common.StorageService.class),
                mock(com.dony.api.favorites.FavoriteRepository.class),
                mock(AnnouncementSearchMapper.class));
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private AnnouncementEntity activeAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, announcementId);
        a.setTravelerId(travelerId);
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setAvailableKg(BigDecimal.valueOf(10));
        a.setTotalKg(BigDecimal.valueOf(20));
        return a;
    }

    private BidEntity pendingBid() {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(announcementId);
        b.setSenderId(senderId);
        b.setStatus(BidStatus.PENDING);
        b.setWeightKg(BigDecimal.valueOf(5));
        return b;
    }

    @Test
    @DisplayName("annonce ACTIVE avec bids ACCEPTED → IN_PROGRESS + bids PENDING expirés + events")
    void activeWithAcceptedBids_becomesInProgress_andExpiresPendingBids() {
        AnnouncementEntity ann = activeAnnouncement();
        BidEntity pending = pendingBid();

        when(announcementRepository.findDepartedActiveAnnouncements(any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT)))
                .thenReturn(true);
        when(bidRepository.findByAnnouncementIdAndStatusIn(announcementId, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED)))
                .thenReturn(List.of(pending));

        service.triggerInProgressTransitions();

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.IN_PROGRESS);
        verify(announcementRepository).save(ann);

        assertThat(pending.getStatus()).isEqualTo(BidStatus.EXPIRED);
        verify(bidRepository).save(pending);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        List<Object> events = eventCaptor.getAllValues();
        assertThat(events).anyMatch(e -> e instanceof AnnouncementInProgressEvent);
        assertThat(events).anyMatch(e -> e instanceof BidExpiredOnDepartureEvent);
    }

    @Test
    @DisplayName("annonce ACTIVE sans bids ACCEPTED → directement COMPLETED")
    void activeWithNoAcceptedBids_becomesCompleted() {
        AnnouncementEntity ann = activeAnnouncement();

        when(announcementRepository.findDepartedActiveAnnouncements(any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT)))
                .thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatusIn(announcementId, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED)))
                .thenReturn(List.of());

        service.triggerInProgressTransitions();

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(eventPublisher, never()).publishEvent(any(AnnouncementInProgressEvent.class));
    }

    @Test
    @DisplayName("aucune annonce à traiter → rien")
    void noAnnouncements_doesNothing() {
        when(announcementRepository.findDepartedActiveAnnouncements(any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of());

        service.triggerInProgressTransitions();

        verify(announcementRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("erreur sur une annonce → les autres continuent")
    void exceptionOnOneAnnouncement_othersStillProcessed() {
        AnnouncementEntity ann1 = activeAnnouncement();
        UUID ann2Id = UUID.randomUUID();
        AnnouncementEntity ann2 = new AnnouncementEntity();
        setId(ann2, ann2Id);
        ann2.setTravelerId(UUID.randomUUID());
        ann2.setStatus(AnnouncementStatus.ACTIVE);
        ann2.setAvailableKg(BigDecimal.valueOf(5));
        ann2.setTotalKg(BigDecimal.valueOf(10));

        when(announcementRepository.findDepartedActiveAnnouncements(any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of(ann1, ann2));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId),
                eq(List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT))))
                .thenThrow(new RuntimeException("DB error"));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ann2Id),
                eq(List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT))))
                .thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(ann2Id), eq(List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED))))
                .thenReturn(List.of());

        service.triggerInProgressTransitions();

        assertThat(ann2.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository).save(ann2);
    }
}
