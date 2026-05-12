package com.dony.api.ratings;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.ratings.dto.PendingRatingResponse;
import com.dony.api.ratings.dto.RatingRequest;
import com.dony.api.ratings.dto.RatingResponse;
import com.dony.api.ratings.dto.RecipientRatingRequest;
import com.dony.api.ratings.dto.TravelerRatingRequest;
import com.dony.api.ratings.dto.UserRatingsSummaryResponse;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.dony.api.ratings.events.RatingCreatedEvent;
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
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RatingService — tests unitaires")
class RatingServiceTest {

    @Mock private RatingRepository ratingRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private RatingService ratingService;

    private static final String SENDER_UID = "uid-sender-001";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final String TRACKING_TOKEN = "tok-abc-123";

    private UserEntity sender;
    private BidEntity bid;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() throws Exception {
        sender = new UserEntity();
        setId(sender, SENDER_ID);
        setField(sender, "firebaseUid", SENDER_UID);
        setField(sender, "phoneNumber", "+33612345678");

        bid = new BidEntity();
        setId(bid, BID_ID);
        setField(bid, "senderId", SENDER_ID);
        setField(bid, "announcementId", ANNOUNCEMENT_ID);
        setField(bid, "status", BidStatus.COMPLETED);
        setField(bid, "trackingToken", TRACKING_TOKEN);
        setField(bid, "updatedAt", LocalDateTime.now().minusDays(1));

        announcement = new AnnouncementEntity();
        setId(announcement, ANNOUNCEMENT_ID);
        setField(announcement, "travelerId", TRAVELER_ID);
    }

    @Nested
    @DisplayName("createRating() — expéditeur")
    class CreateRatingTests {

        @Test
        @DisplayName("notation valide → persist + recalcul + event publié")
        void createRating_valid_persistsAndPublishesEvent() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, SENDER_ID)).thenReturn(false);
            when(ratingRepository.save(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of());

            RatingRequest request = new RatingRequest(BID_ID, 4, "Super voyageur");
            ratingService.createRating(SENDER_UID, request);

            verify(ratingRepository).save(any(RatingEntity.class));
            ArgumentCaptor<RatingCreatedEvent> eventCaptor = ArgumentCaptor.forClass(RatingCreatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getRatedUserId()).isEqualTo(TRAVELER_ID);
            assertThat(eventCaptor.getValue().getStars()).isEqualTo(4);
        }

        @Test
        @DisplayName("bid non livré → 422 UNPROCESSABLE_ENTITY")
        void createRating_bidNotDelivered_throwsException() throws Exception {
            setField(bid, "status", BidStatus.ACCEPTED);
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        @DisplayName("fenêtre expirée → 422 UNPROCESSABLE_ENTITY")
        void createRating_windowExpired_throwsException() throws Exception {
            setField(bid, "updatedAt", LocalDateTime.now().minusDays(10));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("rating-window-expired"));
        }

        @Test
        @DisplayName("notation déjà envoyée → 409 CONFLICT")
        void createRating_alreadyRated_throwsConflict() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, SENDER_ID)).thenReturn(true);

            assertThatThrownBy(() -> ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("expéditeur non propriétaire → 403 FORBIDDEN")
        void createRating_wrongSender_throwsForbidden() throws Exception {
            setField(bid, "senderId", UUID.randomUUID());
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    @Nested
    @DisplayName("createRecipientRating() — destinataire sans compte")
    class RecipientRatingTests {

        @Test
        @DisplayName("token valide + livraison confirmée → persist + recalcul + event")
        void createRecipientRating_valid_persistsAndPublishesEvent() {
            when(bidRepository.findByTrackingToken(TRACKING_TOKEN)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndTrackingToken(BID_ID, TRACKING_TOKEN)).thenReturn(false);
            when(ratingRepository.save(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of());

            ratingService.createRecipientRating(new RecipientRatingRequest(TRACKING_TOKEN, 5, null));

            verify(ratingRepository).save(any(RatingEntity.class));
            verify(eventPublisher).publishEvent(any(RatingCreatedEvent.class));
        }

        @Test
        @DisplayName("token invalide → 404 NOT_FOUND")
        void createRecipientRating_invalidToken_throws404() {
            when(bidRepository.findByTrackingToken("bad-token")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ratingService.createRecipientRating(
                    new RecipientRatingRequest("bad-token", 3, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("déjà noté → 409 CONFLICT")
        void createRecipientRating_alreadyRated_throwsConflict() {
            when(bidRepository.findByTrackingToken(TRACKING_TOKEN)).thenReturn(Optional.of(bid));
            when(ratingRepository.existsByBidIdAndTrackingToken(BID_ID, TRACKING_TOKEN)).thenReturn(true);

            assertThatThrownBy(() -> ratingService.createRecipientRating(
                    new RecipientRatingRequest(TRACKING_TOKEN, 5, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Nested
    @DisplayName("recalculateAverageRating()")
    class RecalculateTests {

        @Test
        @DisplayName("plusieurs notations → moyenne correctement calculée")
        void recalculate_multipleRatings_computesAverage() throws Exception {
            RatingEntity r1 = buildRating(4);
            RatingEntity r2 = buildRating(5);
            RatingEntity r3 = buildRating(3);
            UserEntity traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);

            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID))
                    .thenReturn(List.of(r1, r2, r3));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.save(any())).thenReturn(traveler);

            ratingService.recalculateAverageRating(TRAVELER_ID);

            assertThat(traveler.getAverageRating())
                    .isEqualByComparingTo(new BigDecimal("4.00"));
        }

        @Test
        @DisplayName("aucune notation → pas de mise à jour")
        void recalculate_noRatings_noUpdate() {
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of());

            ratingService.recalculateAverageRating(TRAVELER_ID);

            verify(userRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("createTravelerRating() — voyageur note l'expéditeur")
    class CreateTravelerRatingTests {

        private static final String TRAVELER_UID = "uid-traveler-001";
        private UserEntity traveler;

        @BeforeEach
        void setUpTraveler() throws Exception {
            traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);
            setField(traveler, "firebaseUid", TRAVELER_UID);
            setField(traveler, "firstName", "Moussa");
            setField(traveler, "lastName", "Diallo");
        }

        @Test
        @DisplayName("notation valide → persist + recalcul sur expéditeur + event publié")
        void createTravelerRating_valid_persistsAndPublishesEvent() {
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, TRAVELER_ID)).thenReturn(false);
            when(ratingRepository.save(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ratingRepository.findIncludedRatingsByRatedUserId(SENDER_ID)).thenReturn(List.of());

            var request = new TravelerRatingRequest(BID_ID, 5, "Expéditeur sérieux");
            RatingResponse response = ratingService.createTravelerRating(TRAVELER_UID, request);

            ArgumentCaptor<RatingEntity> captor = ArgumentCaptor.forClass(RatingEntity.class);
            verify(ratingRepository).save(captor.capture());
            assertThat(captor.getValue().getRatedUserId()).isEqualTo(SENDER_ID);
            assertThat(captor.getValue().getRaterId()).isEqualTo(TRAVELER_ID);
            assertThat(captor.getValue().getStars()).isEqualTo(5);

            ArgumentCaptor<RatingCreatedEvent> eventCaptor = ArgumentCaptor.forClass(RatingCreatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getRatedUserId()).isEqualTo(SENDER_ID);
        }

        @Test
        @DisplayName("caller n'est pas le voyageur → 403 FORBIDDEN")
        void createTravelerRating_wrongTraveler_throwsForbidden() throws Exception {
            setField(announcement, "travelerId", UUID.randomUUID()); // autre voyageur
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 4, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("bid non livré → 422 UNPROCESSABLE_ENTITY")
        void createTravelerRating_bidNotDelivered_throws422() throws Exception {
            setField(bid, "status", BidStatus.ACCEPTED);
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("bid-not-delivered"));
        }

        @Test
        @DisplayName("fenêtre expirée → 422 rating-window-expired")
        void createTravelerRating_windowExpired_throws422() throws Exception {
            setField(bid, "updatedAt", LocalDateTime.now().minusDays(10));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 3, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("rating-window-expired"));
        }

        @Test
        @DisplayName("déjà noté → 409 CONFLICT")
        void createTravelerRating_alreadyRated_throws409() {
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, TRAVELER_ID)).thenReturn(true);

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Nested
    @DisplayName("getUserRatings()")
    class GetUserRatingsTests {

        @Test
        @DisplayName("utilisateur avec 3 notations → résumé correct")
        void getUserRatings_withRatings_returnsCorrectSummary() throws Exception {
            UserEntity user = new UserEntity();
            setId(user, TRAVELER_ID);
            setField(user, "averageRating", new BigDecimal("4.33"));

            RatingEntity r1 = buildRating(5); r1.setComment("Top !");
            RatingEntity r2 = buildRating(4); r2.setComment(null);
            RatingEntity r3 = buildRating(4); r3.setComment("Bien");

            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(user));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID))
                    .thenReturn(List.of(r1, r2, r3));
            when(ratingRepository.findByRatedUserId(eq(TRAVELER_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(r1, r2, r3)));

            UserRatingsSummaryResponse response = ratingService.getUserRatings(TRAVELER_ID, 0, 20);

            assertThat(response.ratingCount()).isEqualTo(3);
            assertThat(response.averageRating()).isEqualByComparingTo(new BigDecimal("4.33"));
            assertThat(response.distribution().get(5)).isEqualTo(1L);
            assertThat(response.distribution().get(4)).isEqualTo(2L);
            assertThat(response.distribution().get(1)).isEqualTo(0L);
            assertThat(response.ratings()).hasSize(3);
        }

        @Test
        @DisplayName("utilisateur inexistant → 404")
        void getUserRatings_unknownUser_throws404() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ratingService.getUserRatings(TRAVELER_ID, 0, 20))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("getPendingRating()")
    class GetPendingRatingTests {

        @Test
        @DisplayName("bid en attente → retourne PendingRatingResponse avec isTravelerRating=false pour expéditeur")
        void getPendingRating_senderPending_returnsResponse() throws Exception {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findPendingRatingForUser(SENDER_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            UserEntity traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);
            setField(traveler, "firstName", "Moussa");
            setField(traveler, "lastName", "Diallo");
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            Optional<PendingRatingResponse> result = ratingService.getPendingRating(SENDER_UID);

            assertThat(result).isPresent();
            assertThat(result.get().bidId()).isEqualTo(BID_ID);
            assertThat(result.get().otherPartyId()).isEqualTo(TRAVELER_ID);
            assertThat(result.get().isTravelerRating()).isFalse();
            assertThat(result.get().otherPartyName()).contains("Moussa");
        }

        @Test
        @DisplayName("aucun bid en attente → Optional.empty()")
        void getPendingRating_noPending_returnsEmpty() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findPendingRatingForUser(SENDER_ID)).thenReturn(Optional.empty());

            Optional<PendingRatingResponse> result = ratingService.getPendingRating(SENDER_UID);
            assertThat(result).isEmpty();
        }
    }

    // ─── getMyReceivedRatings() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyReceivedRatings() — notes reçues du connecté")
    class GetMyReceivedRatingsTests {

        @Test
        @DisplayName("uid inconnu → 401 UNAUTHORIZED")
        void getMyReceivedRatings_unknownUid_throws401() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ratingService.getMyReceivedRatings(SENDER_UID, 0, 20))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        @Test
        @DisplayName("uid valide → délègue à getUserRatings avec l'UUID de l'utilisateur")
        void getMyReceivedRatings_validUid_delegatesToGetUserRatings() throws Exception {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            RatingEntity r = buildRating(5);
            when(ratingRepository.findByRatedUserId(eq(SENDER_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(r)));
            when(ratingRepository.findIncludedRatingsByRatedUserId(SENDER_ID)).thenReturn(List.of(r));

            var result = ratingService.getMyReceivedRatings(SENDER_UID, 0, 20);

            assertThat(result).isNotNull();
            assertThat(result.ratingCount()).isEqualTo(1);
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private RatingEntity buildRating(int stars) {
        RatingEntity r = new RatingEntity();
        r.setStars(stars);
        r.setRatedUserId(TRAVELER_ID);
        r.setBidId(BID_ID);
        return r;
    }

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
