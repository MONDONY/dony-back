package com.dony.api.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.CapacityUnit;
import com.dony.api.payments.cash.CommissionProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests de {@link CancellationService#cancelAfterHandover(String, UUID)} (tranche B). */
@ExtendWith(MockitoExtension.class)
class CancellationServiceAfterHandoverTest {

    @Mock private CancellationRepository cancellationRepository;
    @Mock private RematchSuggestionRepository rematchSuggestionRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CancellationService service;

    private static final String FIREBASE = "fb-uid";
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CancellationService(
                cancellationRepository, rematchSuggestionRepository, bidRepository,
                announcementRepository, userRepository, auditService, eventPublisher,
                new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24));
    }

    private UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private BidEntity bid(BidStatus status) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", BID_ID);
        ReflectionTestUtils.setField(b, "senderId", SENDER_ID);
        ReflectionTestUtils.setField(b, "announcementId", ANN_ID);
        ReflectionTestUtils.setField(b, "status", status);
        ReflectionTestUtils.setField(b, "weightKg", new BigDecimal("5"));
        return b;
    }

    private AnnouncementEntity ann(OffsetDateTime departureAt) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANN_ID);
        ReflectionTestUtils.setField(a, "travelerId", TRAVELER_ID);
        a.setDepartureAt(departureAt);
        ReflectionTestUtils.setField(a, "capacityUnit", CapacityUnit.KG_EXACT);
        ReflectionTestUtils.setField(a, "status", AnnouncementStatus.ACTIVE);
        ReflectionTestUtils.setField(a, "availableKg", new BigDecimal("10"));
        return a;
    }

    private void stubFullFlow(UUID callerId, BidStatus status, OffsetDateTime departureAt) {
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(callerId)));
        when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid(status)));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(ann(departureAt)));
        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
    }

    @Test
    void traveler_cancels_handed_over_mints_code_and_refunds() {
        stubFullFlow(TRAVELER_ID, BidStatus.HANDED_OVER, OffsetDateTime.now().plusHours(5));

        service.cancelAfterHandover(FIREBASE, BID_ID);

        ArgumentCaptor<BidEntity> bidCap = ArgumentCaptor.forClass(BidEntity.class);
        verify(bidRepository).save(bidCap.capture());
        assertThat(bidCap.getValue().getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bidCap.getValue().getReturnCode()).hasSize(6);
        assertThat(bidCap.getValue().getReturnDeadline()).isNotNull();

        ArgumentCaptor<CancellationEntity> cCap = ArgumentCaptor.forClass(CancellationEntity.class);
        verify(cancellationRepository).save(cCap.capture());
        assertThat(cCap.getValue().getReason()).isEqualTo("TRAVELER_CANCEL_AFTER_HANDOVER");
        assertThat(cCap.getValue().getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);

        verify(eventPublisher).publishEvent(any(TripCancelledEvent.class));
    }

    @Test
    void sender_cancels_handed_over_uses_sender_reason() {
        stubFullFlow(SENDER_ID, BidStatus.HANDED_OVER, OffsetDateTime.now().plusHours(5));

        service.cancelAfterHandover(FIREBASE, BID_ID);

        ArgumentCaptor<CancellationEntity> cCap = ArgumentCaptor.forClass(CancellationEntity.class);
        verify(cancellationRepository).save(cCap.capture());
        assertThat(cCap.getValue().getReason()).isEqualTo("SENDER_CANCEL_AFTER_HANDOVER");
    }

    @Test
    void unauthorized_caller_is_forbidden() {
        when(userRepository.findByFirebaseUid(FIREBASE))
                .thenReturn(Optional.of(user(UUID.randomUUID())));
        when(bidRepository.findByIdForUpdate(BID_ID))
                .thenReturn(Optional.of(bid(BidStatus.HANDED_OVER)));
        when(announcementRepository.findById(ANN_ID))
                .thenReturn(Optional.of(ann(OffsetDateTime.now().plusHours(5))));

        assertThatThrownBy(() -> service.cancelAfterHandover(FIREBASE, BID_ID))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void not_handed_over_is_conflict() {
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(SENDER_ID)));
        when(bidRepository.findByIdForUpdate(BID_ID))
                .thenReturn(Optional.of(bid(BidStatus.ACCEPTED)));
        when(announcementRepository.findById(ANN_ID))
                .thenReturn(Optional.of(ann(OffsetDateTime.now().plusHours(5))));

        assertThatThrownBy(() -> service.cancelAfterHandover(FIREBASE, BID_ID))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void handed_over_after_departure_is_locked() {
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(SENDER_ID)));
        when(bidRepository.findByIdForUpdate(BID_ID))
                .thenReturn(Optional.of(bid(BidStatus.HANDED_OVER)));
        when(announcementRepository.findById(ANN_ID))
                .thenReturn(Optional.of(ann(OffsetDateTime.now().minusMinutes(1))));

        assertThatThrownBy(() -> service.cancelAfterHandover(FIREBASE, BID_ID))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void bid_not_found_is_404() {
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(SENDER_ID)));
        when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelAfterHandover(FIREBASE, BID_ID))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void existing_cancellation_is_conflict() {
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(SENDER_ID)));
        when(bidRepository.findByIdForUpdate(BID_ID))
                .thenReturn(Optional.of(bid(BidStatus.HANDED_OVER)));
        when(announcementRepository.findById(ANN_ID))
                .thenReturn(Optional.of(ann(OffsetDateTime.now().plusHours(5))));
        when(cancellationRepository.findByBidId(BID_ID))
                .thenReturn(Optional.of(new CancellationEntity()));

        assertThatThrownBy(() -> service.cancelAfterHandover(FIREBASE, BID_ID))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }
}
