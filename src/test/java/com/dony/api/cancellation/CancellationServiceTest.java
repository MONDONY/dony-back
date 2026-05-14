package com.dony.api.cancellation;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.dto.CancellationRequest;
import com.dony.api.cancellation.dto.CancellationResponse;
import com.dony.api.cancellation.dto.RematchSuggestionDto;
import com.dony.api.cancellation.events.TravelerHighCancellationEvent;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancellationService — tests unitaires")
class CancellationServiceTest {

    @Mock private CancellationRepository cancellationRepository;
    @Mock private RematchSuggestionRepository rematchSuggestionRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private CancellationService cancellationService;

    private static final String TRAVELER_UID = "uid-traveler-001";
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(TRAVELER_UID);
        u.setCancellationCount(0);
        setId(u, TRAVELER_ID);
        return u;
    }

    private AnnouncementEntity buildAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(5));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    private BidEntity buildAcceptedBid(UUID senderId) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5));
        b.setStatus(BidStatus.ACCEPTED);
        setId(b, BID_ID);
        return b;
    }

    // ─── cancelTrip ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelTrip()")
    class CancelTripTests {

        @Test
        @DisplayName("annonce active sans bids → annulation réussie + TripCancelledEvent")
        void cancelTrip_activeNoBids_cancelsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Problème personnel");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatus(ANNOUNCEMENT_ID, BidStatus.ACCEPTED))
                    .thenReturn(List.of());
            when(userRepository.save(any())).thenReturn(traveler);

            CancellationResponse result = cancellationService.cancelTrip(TRAVELER_UID, req);

            assertThat(result.announcementId()).isEqualTo(ANNOUNCEMENT_ID);
            assertThat(result.affectedBidsCount()).isEqualTo(0);
            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.CANCELLED);
            assertThat(traveler.getCancellationCount()).isEqualTo(1);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            boolean hasTripCancelledEvent = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof TripCancelledEvent);
            assertThat(hasTripCancelledEvent).isTrue();
        }

        @Test
        @DisplayName("annonce avec bid ACCEPTED → bid annulé + CancellationEntity créée")
        void cancelTrip_withAcceptedBid_cancelsBidAndCreatesCancellation() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            UUID senderId = UUID.randomUUID();
            BidEntity acceptedBid = buildAcceptedBid(senderId);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Vol annulé");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatus(ANNOUNCEMENT_ID, BidStatus.ACCEPTED))
                    .thenReturn(List.of(acceptedBid));
            when(userRepository.save(any())).thenReturn(traveler);
            when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
                CancellationEntity c = inv.getArgument(0);
                setId(c, UUID.randomUUID());
                return c;
            });
            when(announcementRepository.findAll()).thenReturn(List.of());

            CancellationResponse result = cancellationService.cancelTrip(TRAVELER_UID, req);

            assertThat(acceptedBid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            assertThat(result.affectedBidsCount()).isEqualTo(1);
            verify(cancellationRepository).save(any(CancellationEntity.class));
        }

        @Test
        @DisplayName("3ème annulation → TravelerHighCancellationEvent publié")
        void cancelTrip_thirdCancellation_publishesHighCancellationEvent() {
            UserEntity traveler = buildTraveler();
            traveler.setCancellationCount(2); // This cancellation will make it 3
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatus(ANNOUNCEMENT_ID, BidStatus.ACCEPTED))
                    .thenReturn(List.of());
            when(userRepository.save(any())).thenReturn(traveler);

            cancellationService.cancelTrip(TRAVELER_UID, req);

            assertThat(traveler.getCancellationCount()).isEqualTo(3);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());

            boolean hasHighCancellationEvent = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof TravelerHighCancellationEvent);
            assertThat(hasHighCancellationEvent).isTrue();
        }

        @Test
        @DisplayName("2 annulations → TravelerHighCancellationEvent NON publié")
        void cancelTrip_secondCancellation_noHighCancellationEvent() {
            UserEntity traveler = buildTraveler();
            traveler.setCancellationCount(1); // Will become 2
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatus(ANNOUNCEMENT_ID, BidStatus.ACCEPTED))
                    .thenReturn(List.of());
            when(userRepository.save(any())).thenReturn(traveler);

            cancellationService.cancelTrip(TRAVELER_UID, req);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            boolean hasHighCancellationEvent = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof TravelerHighCancellationEvent);
            assertThat(hasHighCancellationEvent).isFalse();
        }

        @Test
        @DisplayName("pas propriétaire de l'annonce → 403 FORBIDDEN")
        void cancelTrip_notOwner_throwsForbidden() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(UUID.randomUUID()); // Different owner
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("annonce déjà annulée → 409 CONFLICT")
        void cancelTrip_alreadyCancelled_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            announcement.setStatus(AnnouncementStatus.CANCELLED);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("already-cancelled"));
        }

        @Test
        @DisplayName("annonce introuvable → 404 NOT_FOUND")
        void cancelTrip_unknownAnnouncement_throwsNotFound() {
            UserEntity traveler = buildTraveler();
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("utilisateur introuvable → 404 NOT_FOUND")
        void cancelTrip_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID,
                    new CancellationRequest(ANNOUNCEMENT_ID, "Raison")))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ─── getRematchSuggestions ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getRematchSuggestions()")
    class RematchSuggestionsTests {

        @Test
        @DisplayName("annonce non-ACTIVE non-CANCELLED → 409 CONFLICT invalid-status")
        void cancelTrip_nonActiveNonCancelled_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            announcement.setStatus(AnnouncementStatus.FULL);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Changement de plan");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("cancellation introuvable → 404 NOT_FOUND")
        void getRematchSuggestions_unknownCancellation_throwsNotFound() {
            UUID cancellationId = UUID.randomUUID();
            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cancellationService.getRematchSuggestions(cancellationId, "uid"))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("caller non-participant → 403 FORBIDDEN")
        void getRematchSuggestions_notParticipant_throwsForbidden() {
            UUID cancellationId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();

            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setBidId(bidId);
            cancellation.setCancelledBy(travelerId);

            BidEntity bid = new BidEntity();
            setId(bid, bidId);
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(UUID.randomUUID()); // some other sender

            AnnouncementEntity announcement = new AnnouncementEntity();
            setId(announcement, announcementId);
            announcement.setTravelerId(travelerId);

            UserEntity outsider = new UserEntity();
            setId(outsider, otherId); // not sender, traveler, nor cancelledBy

            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.of(cancellation));
            when(userRepository.findByFirebaseUid("uid-outsider")).thenReturn(Optional.of(outsider));
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> cancellationService.getRematchSuggestions(cancellationId, "uid-outsider"))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("suggestions présentes avec annonces → retourne DTOs")
        void getRematchSuggestions_withSuggestions_returnsDtos() {
            UUID cancellationId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID altAnnouncementId = UUID.randomUUID();
            UUID suggestionId = UUID.randomUUID();

            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setBidId(bidId);
            cancellation.setCancelledBy(userId);

            BidEntity bid = new BidEntity();
            setId(bid, bidId);
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(userId);

            AnnouncementEntity announcement = new AnnouncementEntity();
            setId(announcement, announcementId);
            announcement.setTravelerId(travelerId);

            UserEntity caller = new UserEntity();
            setId(caller, userId);

            RematchSuggestionEntity suggestion = new RematchSuggestionEntity();
            setId(suggestion, suggestionId);
            suggestion.setCancellationId(cancellationId);
            suggestion.setAnnouncementId(altAnnouncementId);

            AnnouncementEntity altAnnouncement = new AnnouncementEntity();
            setId(altAnnouncement, altAnnouncementId);
            altAnnouncement.setDepartureCity("Paris");
            altAnnouncement.setArrivalCity("Dakar");
            altAnnouncement.setDepartureDate(LocalDate.now().plusDays(7));
            altAnnouncement.setAvailableKg(BigDecimal.TEN);
            altAnnouncement.setPricePerKg(BigDecimal.valueOf(5));

            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.of(cancellation));
            when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(caller));
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
            when(rematchSuggestionRepository.findByCancellationId(cancellationId)).thenReturn(List.of(suggestion));
            when(announcementRepository.findById(altAnnouncementId)).thenReturn(Optional.of(altAnnouncement));

            List<RematchSuggestionDto> result = cancellationService.getRematchSuggestions(cancellationId, "uid");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).departureCity()).isEqualTo("Paris");
        }

        @Test
        @DisplayName("cancellation valide, caller participant, sans suggestions → liste vide")
        void getRematchSuggestions_noSuggestions_returnsEmpty() {
            UUID cancellationId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();

            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setBidId(bidId);
            cancellation.setCancelledBy(travelerId);

            BidEntity bid = new BidEntity();
            setId(bid, bidId);
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(userId);

            AnnouncementEntity announcement = new AnnouncementEntity();
            setId(announcement, announcementId);
            announcement.setTravelerId(travelerId);

            UserEntity caller = new UserEntity();
            setId(caller, userId);
            caller.setFirebaseUid("uid");

            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.of(cancellation));
            when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(caller));
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
            when(rematchSuggestionRepository.findByCancellationId(cancellationId)).thenReturn(List.of());

            var result = cancellationService.getRematchSuggestions(cancellationId, "uid");

            assertThat(result).isEmpty();
        }
    }
}
