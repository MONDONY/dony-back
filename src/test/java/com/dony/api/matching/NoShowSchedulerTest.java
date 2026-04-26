package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NoShowScheduler — tests unitaires")
class NoShowSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private NoShowScheduler scheduler;

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

    @Nested
    @DisplayName("detectNoShows()")
    class DetectNoShowTests {

        @Test
        @DisplayName("bid no-show détecté → status NO_SHOW + noShowCount incrémenté + event")
        void detectNoShows_bidFound_marksNoShowAndPublishesEvent() {
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.save(any())).thenReturn(traveler);

            scheduler.detectNoShows();

            assertThat(bid.getStatus()).isEqualTo(BidStatus.NO_SHOW);
            assertThat(bid.getNoShowAt()).isNotNull();
            assertThat(traveler.getNoShowCount()).isEqualTo(1);
            verify(bidRepository).save(bid);
            verify(userRepository).save(traveler);

            ArgumentCaptor<VoyageurNoShowEvent> captor = ArgumentCaptor.forClass(VoyageurNoShowEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(BID_ID);
            assertThat(captor.getValue().getTravelerId()).isEqualTo(TRAVELER_ID);
            assertThat(captor.getValue().getNoShowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("noShowCount >= 2 → alerte admin créée dans audit_log")
        void detectNoShows_recurringNoShow_adminAlertCreated() throws Exception {
            setField(traveler, "noShowCount", 1); // already 1, will become 2
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.save(any())).thenReturn(traveler);

            scheduler.detectNoShows();

            assertThat(traveler.getNoShowCount()).isEqualTo(2);
            verify(auditService).log(eq("USER"), eq(TRAVELER_ID),
                    eq("ADMIN_ALERT_RECURRING_NO_SHOW"), eq(TRAVELER_ID), any());
        }

        @Test
        @DisplayName("aucun bid à traiter → aucune action")
        void detectNoShows_noBids_noOp() {
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of());

            scheduler.detectNoShows();

            verify(bidRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
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
