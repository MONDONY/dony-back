package com.dony.api.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NoShowScheduler — tests unitaires")
class NoShowSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private NoShowService noShowService;

    @InjectMocks private NoShowScheduler scheduler;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();

    private BidEntity bid;

    @BeforeEach
    void setUp() throws Exception {
        bid = new BidEntity();
        setId(bid, BID_ID);
        setField(bid, "senderId", SENDER_ID);
        setField(bid, "announcementId", ANNOUNCEMENT_ID);
        setField(bid, "status", BidStatus.ACCEPTED);
        setField(bid, "noShowAt", null);
    }

    @Nested
    @DisplayName("detectNoShows()")
    class DetectNoShowTests {

        @Test
        @DisplayName("bid no-show détecté → délègue à NoShowService avec source 'scheduler'")
        void detectNoShows_bidFound_delegatesToService() {
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid));

            scheduler.detectNoShows();

            verify(noShowService).recordTravelerNoShow(BID_ID, "scheduler");
        }

        @Test
        @DisplayName("plusieurs bids → délègue pour chacun")
        void detectNoShows_multipleBids_delegatesForEach() throws Exception {
            BidEntity bid2 = new BidEntity();
            UUID bid2Id = UUID.randomUUID();
            setId(bid2, bid2Id);
            setField(bid2, "status", BidStatus.ACCEPTED);
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid, bid2));

            scheduler.detectNoShows();

            verify(noShowService).recordTravelerNoShow(BID_ID, "scheduler");
            verify(noShowService).recordTravelerNoShow(bid2Id, "scheduler");
        }

        @Test
        @DisplayName("aucun bid à traiter → aucune délégation")
        void detectNoShows_noBids_noOp() {
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of());

            scheduler.detectNoShows();

            verify(noShowService, never()).recordTravelerNoShow(any(), any());
        }

        @Test
        @DisplayName("exception sur un bid → log error et continue avec les suivants")
        void detectNoShows_exceptionOnOneBid_doesNotAbort() throws Exception {
            BidEntity bid2 = new BidEntity();
            UUID bid2Id = UUID.randomUUID();
            setId(bid2, bid2Id);
            setField(bid2, "status", BidStatus.ACCEPTED);
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid, bid2));
            doThrow(new RuntimeException("db error"))
                    .when(noShowService).recordTravelerNoShow(BID_ID, "scheduler");

            // doit passer sans exception — l'erreur est catchée et loggée
            scheduler.detectNoShows();

            // le 2e bid est quand même traité
            verify(noShowService).recordTravelerNoShow(bid2Id, "scheduler");
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
