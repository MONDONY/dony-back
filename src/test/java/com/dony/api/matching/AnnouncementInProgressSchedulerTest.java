package com.dony.api.matching;

import com.dony.api.common.AuditService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementInProgressScheduler")
class AnnouncementInProgressSchedulerTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AnnouncementInProgressScheduler scheduler;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new AnnouncementInProgressScheduler(
                announcementRepository, bidRepository, auditService, eventPublisher);
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
    @DisplayName("annonce ACTIVE avec bids ACCEPTED restants → IN_PROGRESS + bids PENDING expirés + events")
    void activeWithAcceptedBids_becomesInProgress_andExpiresPendingBids() {
        AnnouncementEntity ann = activeAnnouncement();
        BidEntity pending = pendingBid();

        when(announcementRepository.findDepartedActiveAnnouncements(any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatus(announcementId, BidStatus.ACCEPTED))
                .thenReturn(true);
        when(bidRepository.findByAnnouncementIdAndStatus(announcementId, BidStatus.PENDING))
                .thenReturn(List.of(pending));

        scheduler.processInProgressTransitions();

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
    @DisplayName("annonce ACTIVE sans bids ACCEPTED → directement COMPLETED (tous livrés avant départ)")
    void activeWithNoAcceptedBids_becomesCompleted() {
        AnnouncementEntity ann = activeAnnouncement();

        when(announcementRepository.findDepartedActiveAnnouncements(any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatus(announcementId, BidStatus.ACCEPTED))
                .thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatus(announcementId, BidStatus.PENDING))
                .thenReturn(List.of());

        scheduler.processInProgressTransitions();

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository).save(ann);
        verify(eventPublisher, never()).publishEvent(any(AnnouncementInProgressEvent.class));
    }

    @Test
    @DisplayName("annonce ACTIVE sans bids PENDING → IN_PROGRESS sans expiration")
    void activeWithAcceptedNoPending_becomesInProgressNoBidExpiry() {
        AnnouncementEntity ann = activeAnnouncement();

        when(announcementRepository.findDepartedActiveAnnouncements(any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatus(announcementId, BidStatus.ACCEPTED))
                .thenReturn(true);
        when(bidRepository.findByAnnouncementIdAndStatus(announcementId, BidStatus.PENDING))
                .thenReturn(List.of());

        scheduler.processInProgressTransitions();

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.IN_PROGRESS);
        verify(eventPublisher, times(1)).publishEvent(any(AnnouncementInProgressEvent.class));
        verify(eventPublisher, never()).publishEvent(any(BidExpiredOnDepartureEvent.class));
    }

    @Test
    @DisplayName("aucune annonce à traiter → rien")
    void noAnnouncements_doesNothing() {
        when(announcementRepository.findDepartedActiveAnnouncements(any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of());

        scheduler.processInProgressTransitions();

        verify(announcementRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("erreur sur une annonce → les autres continuent à être traitées")
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
        // ann1 throws, ann2 succeeds
        when(bidRepository.existsByAnnouncementIdAndStatus(eq(announcementId), eq(BidStatus.ACCEPTED)))
                .thenThrow(new RuntimeException("DB error"));
        when(bidRepository.existsByAnnouncementIdAndStatus(eq(ann2Id), eq(BidStatus.ACCEPTED)))
                .thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatus(eq(ann2Id), eq(BidStatus.PENDING)))
                .thenReturn(List.of());

        scheduler.processInProgressTransitions();

        // ann2 should still be processed
        assertThat(ann2.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository).save(ann2);
    }
}
