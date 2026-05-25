package com.dony.api.rebooking;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RebookingServiceTest {

    @Mock TravelerSubscriptionRepository travelerSubscriptionRepository;
    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @InjectMocks RebookingService rebookingService;

    @Test
    void subscribeToTraveler_savesNewSubscription() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UserEntity sender = new UserEntity(); setId(sender, senderId);
        UserEntity traveler = new UserEntity(); setId(traveler, travelerId);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(travelerSubscriptionRepository.existsBySenderIdAndTravelerId(senderId, travelerId))
            .thenReturn(false);
        when(travelerSubscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        rebookingService.subscribeToTraveler("uid-sender", travelerId);

        verify(travelerSubscriptionRepository).save(any(TravelerSubscriptionEntity.class));
    }

    @Test
    void subscribeToTraveler_alreadySubscribed_doesNotDuplicate() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UserEntity sender = new UserEntity(); setId(sender, senderId);
        UserEntity traveler = new UserEntity(); setId(traveler, travelerId);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(travelerSubscriptionRepository.existsBySenderIdAndTravelerId(senderId, travelerId))
            .thenReturn(true);

        rebookingService.subscribeToTraveler("uid-sender", travelerId);

        verify(travelerSubscriptionRepository, never()).save(any());
    }

    @Test
    void subscribeToTraveler_unknownSender_throwsDonyNotFoundException() {
        UUID travelerId = UUID.randomUUID();
        when(userRepository.findByFirebaseUid("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rebookingService.subscribeToTraveler("unknown", travelerId))
            .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void subscribeToTraveler_unknownTraveler_throwsDonyNotFoundException() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UserEntity sender = new UserEntity(); setId(sender, senderId);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rebookingService.subscribeToTraveler("uid-sender", travelerId))
            .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void getPastBookings_returnsMappedList() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UserEntity sender = new UserEntity(); setId(sender, senderId);

        Object[] row = new Object[]{
            bidId, travelerId, "Amadou Diallo", null,
            "Paris", "Dakar",
            java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)),
            3L
        };

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findPastBookingsBySender(senderId)).thenReturn(List.<Object[]>of(row));

        List<PastBookingResponse> result = rebookingService.getPastBookings("uid-sender");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).travelerName()).isEqualTo("Amadou Diallo");
        assertThat(result.get(0).departureCity()).isEqualTo("Paris");
        assertThat(result.get(0).arrivalCity()).isEqualTo("Dakar");
        assertThat(result.get(0).completedTripsWithThisTraveler()).isEqualTo(3L);
    }

    @Test
    void getPastBookings_unknownSender_throwsDonyNotFoundException() {
        when(userRepository.findByFirebaseUid("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rebookingService.getPastBookings("unknown"))
            .isInstanceOf(DonyNotFoundException.class);
    }

    // ─── Task 3: rebook ─────────────────────────────────────────────────────────

    @Test
    void rebook_withAvailableAnnouncement_returnsRebookedStatus() throws Exception {
        UUID senderId   = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID pastBidId  = UUID.randomUUID();
        UUID pastAnnId  = UUID.randomUUID();
        UUID newAnnId   = UUID.randomUUID();

        UserEntity sender = new UserEntity(); setId(sender, senderId);

        AnnouncementEntity pastAnnouncement = new AnnouncementEntity();
        setId(pastAnnouncement, pastAnnId);
        pastAnnouncement.setTravelerId(travelerId);
        pastAnnouncement.setDepartureCity("Paris");
        pastAnnouncement.setArrivalCity("Dakar");

        BidEntity pastBid = new BidEntity();
        setId(pastBid, pastBidId);
        pastBid.setSenderId(senderId);
        pastBid.setAnnouncementId(pastAnnId);
        pastBid.setWeightKg(new BigDecimal("5"));
        pastBid.setDeclaredValueEur(new BigDecimal("100"));

        AnnouncementEntity upcoming = new AnnouncementEntity();
        setId(upcoming, newAnnId);

        BidEntity newBid = new BidEntity();
        UUID newBidId = UUID.randomUUID();
        setId(newBid, newBidId);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(pastBidId)).thenReturn(Optional.of(pastBid));
        when(announcementRepository.findById(pastAnnId)).thenReturn(Optional.of(pastAnnouncement));
        when(announcementRepository.findNextUpcomingByTravelerAndCities(
            eq(travelerId), eq("Paris"), eq("Dakar"), any()))
            .thenReturn(Optional.of(upcoming));
        when(bidRepository.save(any())).thenReturn(newBid);

        RebookResponse result = rebookingService.rebook("uid-sender", pastBidId);

        assertThat(result.status()).isEqualTo("REBOOKED");
        assertThat(result.newBidId()).isEqualTo(newBidId);
    }

    @Test
    void rebook_withNoUpcomingTrip_returnsNoTripStatus() throws Exception {
        UUID senderId   = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID pastBidId  = UUID.randomUUID();
        UUID pastAnnId  = UUID.randomUUID();

        UserEntity sender = new UserEntity(); setId(sender, senderId);

        AnnouncementEntity pastAnn = new AnnouncementEntity();
        setId(pastAnn, pastAnnId);
        pastAnn.setTravelerId(travelerId);
        pastAnn.setDepartureCity("Paris");
        pastAnn.setArrivalCity("Dakar");

        BidEntity pastBid = new BidEntity();
        setId(pastBid, pastBidId);
        pastBid.setSenderId(senderId);
        pastBid.setAnnouncementId(pastAnnId);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(pastBidId)).thenReturn(Optional.of(pastBid));
        when(announcementRepository.findById(pastAnnId)).thenReturn(Optional.of(pastAnn));
        when(announcementRepository.findNextUpcomingByTravelerAndCities(
            eq(travelerId), eq("Paris"), eq("Dakar"), any()))
            .thenReturn(Optional.empty());

        RebookResponse result = rebookingService.rebook("uid-sender", pastBidId);

        assertThat(result.status()).isEqualTo("NO_UPCOMING_TRIP");
        assertThat(result.newBidId()).isNull();
        verify(bidRepository, never()).save(any());
    }

    @Test
    void rebook_withBidNotBelongingToSender_throwsAccessDenied() throws Exception {
        UUID senderId   = UUID.randomUUID();
        UUID otherUser  = UUID.randomUUID();
        UUID pastBidId  = UUID.randomUUID();

        UserEntity sender = new UserEntity(); setId(sender, senderId);

        BidEntity pastBid = new BidEntity();
        setId(pastBid, pastBidId);
        pastBid.setSenderId(otherUser); // différent du sender

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(pastBidId)).thenReturn(Optional.of(pastBid));

        assertThatThrownBy(() -> rebookingService.rebook("uid-sender", pastBidId))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void rebook_unknownSender_throwsDonyNotFoundException() {
        when(userRepository.findByFirebaseUid("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rebookingService.rebook("unknown", UUID.randomUUID()))
            .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void rebook_unknownBid_throwsDonyNotFoundException() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UserEntity sender = new UserEntity(); setId(sender, senderId);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rebookingService.rebook("uid-sender", bidId))
            .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void rebook_unknownAnnouncement_throwsDonyNotFoundException() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID annId = UUID.randomUUID();

        UserEntity sender = new UserEntity(); setId(sender, senderId);
        BidEntity bid = new BidEntity(); setId(bid, bidId);
        bid.setSenderId(senderId);
        bid.setAnnouncementId(annId);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rebookingService.rebook("uid-sender", bidId))
            .isInstanceOf(DonyNotFoundException.class);
    }

    // ─── Helper ─────────────────────────────────────────────────────────────────

    private static void setId(Object entity, UUID id) throws Exception {
        Field f = entity.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }
}
