package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NoShowService — tests unitaires")
class NoShowServiceTest {

    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private NoShowService service;

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    private BidEntity bid;
    private UserEntity traveler;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() throws Exception {
        bid = new BidEntity();
        setId(bid, BID_ID);
        setField(bid, "senderId", SENDER_ID);
        setField(bid, "announcementId", ANNOUNCEMENT_ID);
        setField(bid, "status", BidStatus.ACCEPTED);
        setField(bid, "noShowAt", null);

        traveler = new UserEntity();
        setId(traveler, TRAVELER_ID);
        setField(traveler, "noShowCount", 0);

        announcement = new AnnouncementEntity();
        setId(announcement, ANNOUNCEMENT_ID);
        setField(announcement, "travelerId", TRAVELER_ID);
    }

    @Test
    @DisplayName("bid ACCEPTED → status NO_SHOW + noShowCount incrémenté + event publié + audit avec source")
    void recordTravelerNoShow_acceptedBid_marksNoShowAndPublishesEvent() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
        when(bidRepository.save(any())).thenReturn(bid);
        when(userRepository.save(any())).thenReturn(traveler);

        service.recordTravelerNoShow(BID_ID, "sender_report");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.NO_SHOW);
        assertThat(bid.getNoShowAt()).isNotNull();
        assertThat(traveler.getNoShowCount()).isEqualTo(1);
        verify(bidRepository).save(bid);
        verify(userRepository).save(traveler);

        ArgumentCaptor<VoyageurNoShowEvent> eventCaptor = ArgumentCaptor.forClass(VoyageurNoShowEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getBidId()).isEqualTo(BID_ID);
        assertThat(eventCaptor.getValue().getTravelerId()).isEqualTo(TRAVELER_ID);
        assertThat(eventCaptor.getValue().getSenderId()).isEqualTo(SENDER_ID);
        assertThat(eventCaptor.getValue().getNoShowCount()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_NO_SHOW"), eq(TRAVELER_ID),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("source", "sender_report");
    }

    @Test
    @DisplayName("noShowCount >= 2 → alerte admin créée dans audit_log")
    void recordTravelerNoShow_recurring_adminAlertCreated() throws Exception {
        setField(traveler, "noShowCount", 1); // already 1, will become 2
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
        when(bidRepository.save(any())).thenReturn(bid);
        when(userRepository.save(any())).thenReturn(traveler);

        service.recordTravelerNoShow(BID_ID, "scheduler");

        assertThat(traveler.getNoShowCount()).isEqualTo(2);
        verify(auditService).log(eq("USER"), eq(TRAVELER_ID),
                eq("ADMIN_ALERT_RECURRING_NO_SHOW"), eq(TRAVELER_ID), any());
    }

    @Test
    @DisplayName("bid non-ACCEPTED → no-op (idempotence)")
    void recordTravelerNoShow_nonAccepted_noOp() throws Exception {
        setField(bid, "status", BidStatus.NO_SHOW);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

        service.recordTravelerNoShow(BID_ID, "sender_report");

        verify(bidRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("bid introuvable → no-op")
    void recordTravelerNoShow_bidNotFound_noOp() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());

        service.recordTravelerNoShow(BID_ID, "sender_report");

        verify(bidRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("annonce introuvable → bid passe NO_SHOW mais pas d'event (early return)")
    void recordTravelerNoShow_announcementMissing_returnsEarly() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());
        when(bidRepository.save(any())).thenReturn(bid);

        service.recordTravelerNoShow(BID_ID, "scheduler");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.NO_SHOW);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object obj, UUID id) throws Exception {
        Field f = obj.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(obj, id);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f;
        try {
            f = obj.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = obj.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(obj, value);
    }
}
