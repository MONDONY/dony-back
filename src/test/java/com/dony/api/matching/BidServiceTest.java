package com.dony.api.matching;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidRejectRequest;
import com.dony.api.matching.dto.BidRequest;
import com.dony.api.matching.dto.BidResponse;
import com.dony.api.matching.dto.HandoverRequest;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.HandoverDefinedEvent;
import jakarta.servlet.http.HttpServletRequest;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidService — tests unitaires")
class BidServiceTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks private BidService bidService;

    private static final String SENDER_UID = "uid-sender-001";
    private static final String TRAVELER_UID = "uid-traveler-001";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    // ─── Helpers ────────────────────────────────────────────────────────────────

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

    private UserEntity buildSender() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(SENDER_UID);
        u.setPhoneNumber("+33612345678");
        u.getRoles().add(Role.SENDER);
        setId(u, SENDER_ID);
        return u;
    }

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(TRAVELER_UID);
        u.setPhoneNumber("+33611223344");
        u.getRoles().add(Role.TRAVELER);
        setId(u, TRAVELER_ID);
        return u;
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    private BidEntity buildBid() {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setWeightKg(BigDecimal.valueOf(5));
        b.setDeclaredValueEur(BigDecimal.valueOf(100));
        b.setDescription("Vêtements");
        b.setContentCategory("CLOTHING");
        b.setRecipientName("Aminata Diallo");
        b.setRecipientPhone("+221701234567");
        b.setStatus(BidStatus.PENDING);
        setId(b, BID_ID);
        return b;
    }

    private BidRequest buildRequest(BigDecimal weight, BigDecimal value) {
        return new BidRequest(weight, value, "Vêtements", "CLOTHING",
                "Aminata Diallo", "+221701234567", true);
    }

    // ─── createBid ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createBid()")
    class CreateBidTests {

        @BeforeEach
        void setupHttpRequest() {
            lenient().when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
            lenient().when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        }

        @Test
        @DisplayName("demande valide → bid créé + audit enregistré")
        void createBid_valid_createsBidAndAudits() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                    SENDER_ID, ANNOUNCEMENT_ID, List.of(BidStatus.PENDING, BidStatus.ACCEPTED)))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidResponse result = bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5), BigDecimal.valueOf(100)),
                    httpRequest);

            assertThat(result).isNotNull();
            assertThat(result.weightKg()).isEqualByComparingTo(BigDecimal.valueOf(5));
            verify(auditService).log(eq("BID"), any(), eq("BID_CREATED"), any(), any());
            // Task 8: BidCreatedEvent is no longer published from createBid — the
            // webhook (PaymentService.promoteBidOnPaymentAuthorized) does it now.
            verify(eventPublisher, never()).publishEvent(any(BidCreatedEvent.class));
        }

        @Test
        @DisplayName("poids dépasse la capacité → 422 UNPROCESSABLE_ENTITY")
        void createBid_weightExceedsCapacity_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement(); // 20 kg available

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(25), BigDecimal.valueOf(100)), // 25 > 20
                    httpRequest))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("weight-exceeds-capacity");
                    });
        }

        @Test
        @DisplayName("valeur déclarée > 500€ → 422 UNPROCESSABLE_ENTITY")
        void createBid_valueTooHigh_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(5), BigDecimal.valueOf(501)), // 501 > 500
                    httpRequest))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("value-exceeds-limit");
                    });
        }

        @Test
        @DisplayName("disclaimer non signé → 422 UNPROCESSABLE_ENTITY")
        void createBid_disclaimerNotSigned_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            BidRequest req = new BidRequest(BigDecimal.valueOf(5), BigDecimal.valueOf(100),
                    "Desc", "CAT", "Recip", "+221", false); // not signed

            assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, req, httpRequest))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("disclaimer-not-signed"));
        }

        @Test
        @DisplayName("bid déjà existant → 409 CONFLICT")
        void createBid_alreadyHasBid_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(true);

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5), BigDecimal.valueOf(100)),
                    httpRequest))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("already-bid"));
        }

        @Test
        @DisplayName("bid sur sa propre annonce → 409 CONFLICT")
        void createBid_ownAnnouncement_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setTravelerId(SENDER_ID); // Same user is the traveler

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5), BigDecimal.valueOf(100)),
                    httpRequest))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("cannot-bid-own-announcement"));
        }

        @Test
        @DisplayName("annonce non ACTIVE → 409 CONFLICT")
        void createBid_announcementNotActive_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setStatus(AnnouncementStatus.FULL);

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5), BigDecimal.valueOf(100)),
                    httpRequest))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("announcement-not-active"));
        }

        @Test
        @DisplayName("IP extraite du header X-Forwarded-For (proxy)")
        void createBid_withForwardedFor_extractsClientIp() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 198.51.100.2");
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(bidRepository.save(any())).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(5), BigDecimal.valueOf(100)), httpRequest);

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            // Last hop in X-Forwarded-For is used (added by trusted proxy, not spoofable by client)
            assertThat(captor.getValue().getDisclaimerSignedIp()).isEqualTo("198.51.100.2");
        }

        @Test
        @DisplayName("utilisateur sans rôle SENDER → rôle ajouté automatiquement")
        void createBid_userWithoutSenderRole_addsSenderRole() {
            UserEntity sender = new UserEntity();
            sender.setFirebaseUid(SENDER_UID);
            setId(sender, SENDER_ID);
            // No SENDER role

            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(userRepository.save(any())).thenReturn(sender);
            when(bidRepository.save(any())).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(5), BigDecimal.valueOf(100)), httpRequest);

            assertThat(sender.getRoles()).contains(Role.SENDER);
        }
    }

    // ─── acceptBid ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("acceptBid()")
    class AcceptBidTests {

        @Test
        @DisplayName("bid valide → accepté + tokens générés + capacité réduite + event")
        void acceptBid_valid_acceptsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidResponse result = bidService.acceptBid(BID_ID, TRAVELER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            assertThat(bid.getQrToken()).isNotNull();
            assertThat(bid.getTrackingNumber()).startsWith("DON-");
            assertThat(bid.getTrackingToken()).isNotNull();
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(15));

            ArgumentCaptor<BidAcceptedEvent> captor = ArgumentCaptor.forClass(BidAcceptedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(BID_ID);
        }

        @Test
        @DisplayName("capacité insuffisante → 409 CONFLICT")
        void acceptBid_insufficientCapacity_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAvailableKg(BigDecimal.valueOf(3)); // Less than bid weight (5 kg)

            BidEntity bid = buildBid();
            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("capacity-insufficient"));
        }

        @Test
        @DisplayName("pas propriétaire de l'annonce → 403 FORBIDDEN")
        void acceptBid_notOwner_throwsForbidden() {
            UserEntity otherTraveler = new UserEntity();
            setId(otherTraveler, UUID.randomUUID());
            otherTraveler.setFirebaseUid(TRAVELER_UID);

            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(otherTraveler));

            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("bid non PENDING → 409 CONFLICT")
        void acceptBid_notPending_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED); // Already accepted

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("acceptBid remplit exactement la capacité → annonce passe FULL")
        void acceptBid_fillsCapacity_becomesFulls() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAvailableKg(BigDecimal.valueOf(5)); // exact match avec le bid
            BidEntity bid = buildBid(); // weightKg = 5

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            bidService.acceptBid(BID_ID, TRAVELER_UID);

            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        }
    }

    // ─── rejectBid ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rejectBid()")
    class RejectBidTests {

        @Test
        @DisplayName("bid valide → rejeté + raison enregistrée + event publié")
        void rejectBid_valid_rejectsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.rejectBid(BID_ID, TRAVELER_UID, new BidRejectRequest("Trop lourd"));

            assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
            assertThat(bid.getRejectionReason()).isEqualTo("Trop lourd");

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(BID_ID);
        }
    }

    // ─── cancelBid ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelBid()")
    class CancelBidTests {

        @Test
        @DisplayName("bid PENDING annulé → kg NON restitués (only ACCEPTED triggers restore)")
        void cancelBid_pendingBid_cancelsWithoutRestoringKg() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.save(any())).thenReturn(bid);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(
                    Optional.of(buildAnnouncement()));

            bidService.cancelBid(BID_ID, SENDER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("cancelBid publie BidRejectedEvent avec reason CANCELLED_BY_SENDER")
        void cancelBid_publishes_BidRejectedEvent_with_CANCELLED_BY_SENDER_reason() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.save(any())).thenReturn(bid);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(
                    Optional.of(buildAnnouncement()));

            bidService.cancelBid(BID_ID, SENDER_UID);

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(bid.getId());
            assertThat(captor.getValue().getSenderId()).isEqualTo(bid.getSenderId());
            assertThat(captor.getValue().getReason()).isEqualTo("CANCELLED_BY_SENDER");
        }

        @Test
        @DisplayName("bid ACCEPTED annulé → kg restitués à l'annonce")
        void cancelBid_acceptedBid_restoresKg() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.cancelBid(BID_ID, SENDER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(25)); // 20+5
            verify(announcementRepository).save(announcement);
        }

        @Test
        @DisplayName("bid ACCEPTED annulé sur annonce FULL → annonce repasse ACTIVE")
        void cancelBid_acceptedBidOnFullAnnouncement_reactivates() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAvailableKg(BigDecimal.ZERO);
            announcement.setStatus(AnnouncementStatus.FULL);
            BidEntity bid = buildBid(); // weightKg = 5
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.cancelBid(BID_ID, SENDER_UID);

            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(5));
        }

        @Test
        @DisplayName("pas propriétaire du bid → 403 FORBIDDEN")
        void cancelBid_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            setId(otherUser, UUID.randomUUID());
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, SENDER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("bid déjà annulé → 409 CONFLICT")
        void cancelBid_alreadyCancelled_throwsConflict() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.CANCELLED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, SENDER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("bid REJECTED → 409 CONFLICT")
        void cancelBid_alreadyRejected_throwsConflict() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.REJECTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, SENDER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }
    }

    // ─── setHandover ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setHandover()")
    class SetHandoverTests {

        @Test
        @DisplayName("fenêtre valide → handover défini + event publié")
        void setHandover_validWindow_setsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            LocalDateTime start = LocalDateTime.now().plusDays(5);
            LocalDateTime end = start.plusHours(2);
            HandoverRequest req = new HandoverRequest("Gare du Nord", start, end);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.setHandover(BID_ID, TRAVELER_UID, req);

            assertThat(bid.getHandoverLocation()).isEqualTo("Gare du Nord");
            assertThat(bid.getHandoverWindowStart()).isEqualTo(start);
            assertThat(bid.getHandoverWindowEnd()).isEqualTo(end);

            ArgumentCaptor<HandoverDefinedEvent> captor =
                    ArgumentCaptor.forClass(HandoverDefinedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(BID_ID);
        }

        @Test
        @DisplayName("fin avant début → 422 UNPROCESSABLE_ENTITY")
        void setHandover_endBeforeStart_throwsUnprocessable() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            LocalDateTime start = LocalDateTime.now().plusDays(5);
            LocalDateTime end = start.minusHours(1); // End before start
            HandoverRequest req = new HandoverRequest("Gare du Nord", start, end);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.setHandover(BID_ID, TRAVELER_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("invalid-window"));
        }

        @Test
        @DisplayName("bid non accepté → 409 CONFLICT")
        void setHandover_bidNotAccepted_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid(); // status = PENDING

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            HandoverRequest req = new HandoverRequest("Loc", LocalDateTime.now(),
                    LocalDateTime.now().plusHours(1));

            assertThatThrownBy(() -> bidService.setHandover(BID_ID, TRAVELER_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("bid-not-accepted"));
        }
    }

    // ─── hideBid ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hideBidForSender()")
    class HideBidTests {

        @Test
        @DisplayName("propriétaire → bid masqué")
        void hideBidForSender_owner_hides() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.hideBidForSender(BID_ID, SENDER_UID);

            assertThat(bid.isDeletedBySender()).isTrue();
        }

        @Test
        @DisplayName("pas propriétaire → 403 FORBIDDEN")
        void hideBidForSender_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            setId(otherUser, UUID.randomUUID());
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> bidService.hideBidForSender(BID_ID, SENDER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // ─── confirmPresence ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("confirmPresence()")
    class ConfirmPresenceTests {

        @Test
        @DisplayName("bid accepté → voyageurConfirmed = true")
        void confirmPresence_acceptedBid_setsConfirmed() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.confirmPresence(BID_ID, TRAVELER_UID);

            assertThat(bid.isVoyageurConfirmed()).isTrue();
        }
    }

    // ─── markH2AlertSent ───────────────────────────────────────────────────────

    @Test
    @DisplayName("markH2AlertSent → heure alert enregistrée")
    void markH2AlertSent_existingBid_setsAlertTime() {
        BidEntity bid = buildBid();
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(bidRepository.save(any())).thenReturn(bid);

        bidService.markH2AlertSent(BID_ID);

        assertThat(bid.getH2AlertSentAt()).isNotNull();
    }

    @Test
    @DisplayName("markH2AlertSent bid introuvable → no-op silencieux")
    void markH2AlertSent_unknownBid_doesNothing() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> bidService.markH2AlertSent(BID_ID)).doesNotThrowAnyException();
        verify(bidRepository, never()).save(any());
    }

    // ─── getMyBids ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMyBids → retourne les bids non masqués de l'expéditeur")
    void getMyBids_returnsVisibleBids() {
        UserEntity sender = buildSender();
        BidEntity visibleBid = buildBid();
        BidEntity hiddenBid = buildBid();
        setId(hiddenBid, UUID.randomUUID());
        hiddenBid.setDeletedBySender(true);

        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(bidRepository.findBySenderId(SENDER_ID)).thenReturn(List.of(visibleBid, hiddenBid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(buildAnnouncement()));

        List<BidResponse> result = bidService.getMyBids(SENDER_UID);

        assertThat(result).hasSize(1);
    }

    // ─── getBidById ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("expéditeur appelle getBidById → confirmationCode visible")
    void getBidById_callerIsSender_showsConfirmationCode() {
        UserEntity sender = buildSender();
        BidEntity bid = buildBid();
        bid.setConfirmationCode("654321");
        AnnouncementEntity announcement = buildAnnouncement();

        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

        BidResponse result = bidService.getBidById(BID_ID, SENDER_UID);

        assertThat(result.confirmationCode()).isEqualTo("654321");
    }

    @Test
    @DisplayName("tiers → 403 FORBIDDEN")
    void getBidById_foreignerForbidden() {
        UserEntity stranger = new UserEntity();
        setId(stranger, UUID.randomUUID());
        stranger.setFirebaseUid(SENDER_UID);
        BidEntity bid = buildBid();
        AnnouncementEntity announcement = buildAnnouncement();

        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(stranger));

        assertThatThrownBy(() -> bidService.getBidById(BID_ID, SENDER_UID))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─── getBidsForAnnouncement ────────────────────────────────────────────────

    @Test
    @DisplayName("propriétaire de l'annonce → retourne les bids non masqués")
    void getBidsForAnnouncement_owner_returnsBids() {
        UserEntity traveler = buildTraveler();
        AnnouncementEntity announcement = buildAnnouncement();
        BidEntity visible = buildBid();
        BidEntity hidden = buildBid();
        setId(hidden, UUID.randomUUID());
        hidden.setDeletedByTraveler(true);

        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
        when(bidRepository.findByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(List.of(visible, hidden));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        List<BidResponse> result = bidService.getBidsForAnnouncement(ANNOUNCEMENT_ID, TRAVELER_UID);

        assertThat(result).hasSize(1);
    }

    // ─── rejectBid null request ────────────────────────────────────────────────

    @Test
    @DisplayName("rejectBid avec request null → pas de raison de rejet")
    void rejectBid_nullRequest_noRejectionReason() {
        UserEntity traveler = buildTraveler();
        AnnouncementEntity announcement = buildAnnouncement();
        BidEntity bid = buildBid();

        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
        when(bidRepository.save(any())).thenReturn(bid);
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        bidService.rejectBid(BID_ID, TRAVELER_UID, null);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
        assertThat(bid.getRejectionReason()).isNull();
    }

    // ─── hideBidForTraveler ────────────────────────────────────────────────────

    @Nested
    @DisplayName("hideBidForTraveler()")
    class HideBidTravelerTests {

        @Test
        @DisplayName("bid REJECTED → masqué pour le voyageur")
        void hideBidForTraveler_rejectedBid_dismisses() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.REJECTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.hideBidForTraveler(BID_ID, TRAVELER_UID);

            assertThat(bid.isDeletedByTraveler()).isTrue();
        }

        @Test
        @DisplayName("pas propriétaire → 403 FORBIDDEN")
        void hideBidForTraveler_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            setId(otherUser, UUID.randomUUID());
            otherUser.setFirebaseUid(TRAVELER_UID);
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.REJECTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> bidService.hideBidForTraveler(BID_ID, TRAVELER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("bid PENDING (non REJECTED/CANCELLED) → 409 CONFLICT")
        void hideBidForTraveler_activeBid_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid(); // status = PENDING

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.hideBidForTraveler(BID_ID, TRAVELER_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                            .isEqualTo("invalid-bid-status"));
        }
    }
}
