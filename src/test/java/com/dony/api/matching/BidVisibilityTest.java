package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidVisibilityTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private BidService bidService;

    private UserEntity traveler;
    private UserEntity sender;
    private AnnouncementEntity announcement;
    private BidEntity awaitingPaymentBid;
    private BidEntity pendingBid;

    @BeforeEach
    void setUp() {
        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", UUID.randomUUID());
        traveler.setFirebaseUid("uid-traveler");

        sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", UUID.randomUUID());
        sender.setFirebaseUid("uid-sender");

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(traveler.getId());
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setAvailableKg(new BigDecimal("10"));
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");

        awaitingPaymentBid = new BidEntity();
        ReflectionTestUtils.setField(awaitingPaymentBid, "id", UUID.randomUUID());
        awaitingPaymentBid.setAnnouncementId(announcement.getId());
        awaitingPaymentBid.setSenderId(sender.getId());
        awaitingPaymentBid.setStatus(BidStatus.AWAITING_PAYMENT);
        awaitingPaymentBid.setWeightKg(new BigDecimal("1"));
        awaitingPaymentBid.setDeclaredValueEur(new BigDecimal("100"));

        pendingBid = new BidEntity();
        ReflectionTestUtils.setField(pendingBid, "id", UUID.randomUUID());
        pendingBid.setAnnouncementId(announcement.getId());
        pendingBid.setSenderId(sender.getId());
        pendingBid.setStatus(BidStatus.PENDING);
        pendingBid.setWeightKg(new BigDecimal("1"));
        pendingBid.setDeclaredValueEur(new BigDecimal("100"));
    }

    @Test
    void traveler_does_not_see_AWAITING_PAYMENT_bids() {
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(bidRepository.findByAnnouncementId(announcement.getId()))
            .thenReturn(List.of(awaitingPaymentBid, pendingBid));

        var result = bidService.getBidsForAnnouncement(announcement.getId(), "uid-traveler");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("PENDING");
    }

    @Test
    void sender_sees_their_AWAITING_PAYMENT_bids() {
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findBySenderId(sender.getId()))
            .thenReturn(List.of(awaitingPaymentBid, pendingBid));

        var result = bidService.getMyBids("uid-sender");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(b -> b.status())
            .containsExactlyInAnyOrder("AWAITING_PAYMENT", "PENDING");
    }
}
