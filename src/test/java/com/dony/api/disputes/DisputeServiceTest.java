package com.dony.api.disputes;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.disputes.dto.DisputeResponse;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    @Mock private DisputeRepository disputeRepository;
    @Mock private AuditService auditService;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;

    private DisputeService service;

    private static final UUID BID_ID      = UUID.randomUUID();
    private static final UUID SENDER_ID   = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DisputeService(disputeRepository, auditService,
                bidRepository, announcementRepository, userRepository);
    }

    @Nested
    class OpenSenderNoShowDispute {

        @Test
        void success_createsDisputeWithCorrectFields() {
            when(disputeRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
            when(disputeRepository.save(any())).thenAnswer(inv -> {
                DisputeEntity d = inv.getArgument(0);
                ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
                return d;
            });

            DisputeEntity result = service.openSenderNoShowDispute(BID_ID, SENDER_ID, TRAVELER_ID);

            assertThat(result.getBidId()).isEqualTo(BID_ID);
            assertThat(result.getSenderId()).isEqualTo(SENDER_ID);
            assertThat(result.getTravelerId()).isEqualTo(TRAVELER_ID);
            assertThat(result.getType()).isEqualTo("SENDER_NO_SHOW_CONTESTED");
            assertThat(result.getStatus()).isEqualTo("OPEN");
            assertThat(result.isRefundFrozen()).isTrue();

            verify(disputeRepository).save(any(DisputeEntity.class));
            verify(auditService).log(eq("DISPUTE"), any(UUID.class),
                    eq("SENDER_NO_SHOW_DISPUTE_OPENED"), eq(SENDER_ID), any(Map.class));
        }

        @Test
        void idempotent_returnsExistingDisputeRegardlessOfStatus_noSaveNoAudit() {
            DisputeEntity existing = new DisputeEntity();
            ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
            existing.setBidId(BID_ID);
            existing.setSenderId(SENDER_ID);
            existing.setTravelerId(TRAVELER_ID);
            existing.setType("SENDER_NO_SHOW_CONTESTED");
            existing.setStatus("RESOLVED");
            existing.setRefundFrozen(true);

            when(disputeRepository.findByBidId(BID_ID)).thenReturn(Optional.of(existing));

            DisputeEntity result = service.openSenderNoShowDispute(BID_ID, SENDER_ID, TRAVELER_ID);

            assertThat(result).isSameAs(existing);
            verify(disputeRepository, never()).save(any());
            verifyNoInteractions(auditService);
        }
    }

    @Nested
    class OpenDeliveryNoShowDispute {

        @Test
        void openDeliveryNoShowDispute_createsDisputeWithGivenType() {
            UUID bidId = UUID.randomUUID();
            UUID senderId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            when(disputeRepository.findByBidIdAndType(bidId, "RECIPIENT_NO_SHOW_CONTESTED"))
                    .thenReturn(Optional.empty());
            when(disputeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            DisputeEntity result = service.openDeliveryNoShowDispute(
                    bidId, senderId, travelerId, "RECIPIENT_NO_SHOW_CONTESTED");

            assertThat(result.getType()).isEqualTo("RECIPIENT_NO_SHOW_CONTESTED");
            assertThat(result.getStatus()).isEqualTo("OPEN");
            assertThat(result.isRefundFrozen()).isTrue();
            assertThat(result.getSenderId()).isEqualTo(senderId);
            assertThat(result.getTravelerId()).isEqualTo(travelerId);
        }

        @Test
        void openDeliveryNoShowDispute_idempotent_returnsExistingIfAlreadyOpen() {
            UUID bidId = UUID.randomUUID();
            DisputeEntity existing = new DisputeEntity();
            existing.setType("TRAVELER_DELIVERY_NO_SHOW");
            when(disputeRepository.findByBidIdAndType(bidId, "TRAVELER_DELIVERY_NO_SHOW"))
                    .thenReturn(Optional.of(existing));

            DisputeEntity result = service.openDeliveryNoShowDispute(
                    bidId, UUID.randomUUID(), UUID.randomUUID(), "TRAVELER_DELIVERY_NO_SHOW");

            assertThat(result).isSameAs(existing);
            verify(disputeRepository, never()).save(any());
        }
    }

    @Test
    void getDisputesForUser_returnsUnion_withMyRolePerDispute() {
        UUID me = UUID.randomUUID();
        DisputeEntity asSender = dispute(me, UUID.randomUUID());      // je suis sender
        DisputeEntity asTraveler = dispute(UUID.randomUUID(), me);    // je suis traveler
        when(disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(me, me))
                .thenReturn(List.of(asSender, asTraveler));
        when(bidRepository.findAllById(any())).thenReturn(List.of());
        when(announcementRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        List<DisputeResponse> result = service.getDisputesForUser(me);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).myRole()).isEqualTo("SENDER");
        assertThat(result.get(1).myRole()).isEqualTo("TRAVELER");
    }

    @Test
    void getDisputesForUser_mapsTripContextAndOtherParty() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID annId = UUID.randomUUID();

        DisputeEntity d = dispute(me, other);
        d.setBidId(bidId);
        when(disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(me, me))
                .thenReturn(List.of(d));

        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setAnnouncementId(annId);
        bid.setWeightKg(new BigDecimal("5.00"));
        when(bidRepository.findAllById(any())).thenReturn(List.of(bid));

        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", annId);
        ann.setDepartureCity("Lyon");
        ann.setArrivalCity("Abidjan");
        ann.setDepartureCountryCode("FR");
        ann.setArrivalCountryCode("CI");
        ann.setDepartureDate(LocalDate.of(2026, 6, 20));
        when(announcementRepository.findAllById(any())).thenReturn(List.of(ann));

        UserEntity otherUser = new UserEntity();
        ReflectionTestUtils.setField(otherUser, "id", other);
        otherUser.setFirstName("Awa");
        otherUser.setLastName("K.");
        when(userRepository.findAllById(any())).thenReturn(List.of(otherUser));

        DisputeResponse r = service.getDisputesForUser(me).get(0);

        assertThat(r.departureCity()).isEqualTo("Lyon");
        assertThat(r.arrivalCity()).isEqualTo("Abidjan");
        assertThat(r.tripDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(r.weightKg()).isEqualByComparingTo("5.00");
        assertThat(r.otherPartyName()).isEqualTo("Awa K.");
    }

    @Test
    void getDisputesForUser_missingBidOrAnnouncement_yieldsNullContext() {
        UUID me = UUID.randomUUID();
        DisputeEntity d = dispute(me, UUID.randomUUID());
        d.setBidId(UUID.randomUUID()); // bid soft-deleted → findAllById vide
        when(disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(me, me))
                .thenReturn(List.of(d));
        when(bidRepository.findAllById(any())).thenReturn(List.of());
        when(announcementRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        DisputeResponse r = service.getDisputesForUser(me).get(0);

        assertThat(r.departureCity()).isNull();
        assertThat(r.weightKg()).isNull();
        assertThat(r.otherPartyName()).isNull();
    }

    @Test
    void getDisputesForUser_beneficiaryFlag() {
        UUID me = UUID.randomUUID();
        DisputeEntity mine = dispute(me, UUID.randomUUID());
        mine.setBeneficiaryUserId(me);
        DisputeEntity notMine = dispute(me, UUID.randomUUID());
        notMine.setBeneficiaryUserId(UUID.randomUUID());
        when(disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(me, me))
                .thenReturn(List.of(mine, notMine));
        when(bidRepository.findAllById(any())).thenReturn(List.of());
        when(announcementRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        List<DisputeResponse> result = service.getDisputesForUser(me);
        assertThat(result.get(0).isBeneficiary()).isTrue();
        assertThat(result.get(1).isBeneficiary()).isFalse();
    }

    private static DisputeEntity dispute(UUID senderId, UUID travelerId) {
        DisputeEntity d = new DisputeEntity();
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        d.setSenderId(senderId);
        d.setTravelerId(travelerId);
        d.setType("SENDER_NO_SHOW_CONTESTED");
        d.setStatus("OPEN");
        return d;
    }
}
