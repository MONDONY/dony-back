package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementCompletionListener")
class AnnouncementCompletionListenerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;

    private AnnouncementCompletionListener listener;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new AnnouncementCompletionListener(
                bidRepository, announcementRepository, auditService);
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

    private BidEntity completedBid() {
        BidEntity bid = new BidEntity();
        setId(bid, bidId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.COMPLETED);
        return bid;
    }

    private AnnouncementEntity announcement(AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, announcementId);
        a.setTravelerId(travelerId);
        a.setStatus(status);
        return a;
    }

    private DeliveryConfirmedEvent event() {
        return new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId);
    }

    @Test
    @DisplayName("dernier bid livré + plus aucun ACCEPTED → annonce COMPLETED + audit")
    void lastBidCompleted_setsAnnouncementCompleted() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.ACTIVE);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatus(announcementId, BidStatus.ACCEPTED))
                .thenReturn(false);

        listener.onDeliveryConfirmed(event());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository).save(ann);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(
                eq("ANNOUNCEMENT"),
                eq(travelerId),
                eq("ANNOUNCEMENT_COMPLETED"),
                eq(announcementId),
                captor.capture());
        assertThat(captor.getValue()).containsEntry("previousStatus", "ACTIVE");
        assertThat(captor.getValue()).containsEntry("lastDeliveredBidId", bidId.toString());
    }

    @Test
    @DisplayName("il reste un bid ACCEPTED → annonce inchangée")
    void otherAcceptedBidsRemain_keepsStatus() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.FULL);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatus(announcementId, BidStatus.ACCEPTED))
                .thenReturn(true);

        listener.onDeliveryConfirmed(event());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("annonce déjà COMPLETED → idempotent (pas d'audit)")
    void alreadyCompleted_isIdempotent() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.COMPLETED);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(event());

        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
        verify(bidRepository, never()).existsByAnnouncementIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("annonce CANCELLED → on ne touche pas")
    void cancelled_doesNothing() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.CANCELLED);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(event());

        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bid inconnu → log warn, no-op")
    void unknownBid_skips() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(event());

        verify(announcementRepository, never()).findById(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("annonce inconnue → log warn, no-op")
    void unknownAnnouncement_skips() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(event());

        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
