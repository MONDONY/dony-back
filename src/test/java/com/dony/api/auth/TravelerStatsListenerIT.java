package com.dony.api.auth;

import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.TransportMode;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TravelerStatsListenerIT {

    @Autowired UserRepository userRepository;
    @Autowired BidRepository bidRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;

    private UserEntity persistTraveler(int totalTrips) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setPhoneNumber("+33" + System.nanoTime());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(totalTrips);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity persistCompletedBid(UUID announcementId, UUID senderId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("2.50"));
        bid.setDeclaredValueEur(new BigDecimal("100.00"));
        bid.setStatus(BidStatus.COMPLETED);
        return bidRepository.save(bid);
    }

    @Test
    void increments_total_trips_after_commit_on_first_bid() {
        UserEntity traveler = persistTraveler(3);
        AnnouncementEntity ann = persistAnnouncement(traveler.getId());
        BidEntity bid = persistCompletedBid(ann.getId(), UUID.randomUUID());

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                        bid.getId(), bid.getSenderId(), traveler.getId())));

        UserEntity reloadedUser = userRepository.findById(traveler.getId()).orElseThrow();
        AnnouncementEntity reloadedAnn = announcementRepository.findById(ann.getId()).orElseThrow();
        assertThat(reloadedUser.getTotalTrips()).isEqualTo(4);
        assertThat(reloadedAnn.isTotalTripsCounted()).isTrue();
    }

    @Test
    void three_bids_completed_on_same_announcement_increment_only_once() {
        UserEntity traveler = persistTraveler(0);
        AnnouncementEntity ann = persistAnnouncement(traveler.getId());
        BidEntity bid1 = persistCompletedBid(ann.getId(), UUID.randomUUID());
        BidEntity bid2 = persistCompletedBid(ann.getId(), UUID.randomUUID());
        BidEntity bid3 = persistCompletedBid(ann.getId(), UUID.randomUUID());

        for (BidEntity bid : new BidEntity[]{bid1, bid2, bid3}) {
            transactionTemplate.executeWithoutResult(status ->
                    eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                            bid.getId(), bid.getSenderId(), traveler.getId())));
        }

        UserEntity reloadedUser = userRepository.findById(traveler.getId()).orElseThrow();
        AnnouncementEntity reloadedAnn = announcementRepository.findById(ann.getId()).orElseThrow();
        assertThat(reloadedUser.getTotalTrips()).isEqualTo(1);
        assertThat(reloadedAnn.isTotalTripsCounted()).isTrue();
    }

    @Test
    void announcement_with_zero_completed_bid_does_not_increment() {
        UserEntity traveler = persistTraveler(2);
        AnnouncementEntity ann = persistAnnouncement(traveler.getId());

        // No event published — no bid ever reached COMPLETED
        UserEntity reloadedUser = userRepository.findById(traveler.getId()).orElseThrow();
        AnnouncementEntity reloadedAnn = announcementRepository.findById(ann.getId()).orElseThrow();
        assertThat(reloadedUser.getTotalTrips()).isEqualTo(2);
        assertThat(reloadedAnn.isTotalTripsCounted()).isFalse();
    }

    @Test
    void new_announcement_increments_again() {
        UserEntity traveler = persistTraveler(0);

        AnnouncementEntity ann1 = persistAnnouncement(traveler.getId());
        BidEntity bid1 = persistCompletedBid(ann1.getId(), UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                        bid1.getId(), bid1.getSenderId(), traveler.getId())));

        AnnouncementEntity ann2 = persistAnnouncement(traveler.getId());
        BidEntity bid2 = persistCompletedBid(ann2.getId(), UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                        bid2.getId(), bid2.getSenderId(), traveler.getId())));

        UserEntity reloadedUser = userRepository.findById(traveler.getId()).orElseThrow();
        assertThat(reloadedUser.getTotalTrips()).isEqualTo(2);
        assertThat(announcementRepository.findById(ann1.getId()).orElseThrow().isTotalTripsCounted()).isTrue();
        assertThat(announcementRepository.findById(ann2.getId()).orElseThrow().isTotalTripsCounted()).isTrue();
    }

    @Test
    void does_not_increment_when_parent_transaction_rolls_back() {
        UserEntity traveler = persistTraveler(7);
        AnnouncementEntity ann = persistAnnouncement(traveler.getId());
        BidEntity bid = persistCompletedBid(ann.getId(), UUID.randomUUID());

        try {
            transactionTemplate.executeWithoutResult(status -> {
                eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                        bid.getId(), bid.getSenderId(), traveler.getId()));
                status.setRollbackOnly();
            });
        } catch (Exception ignored) {
            // some Spring versions throw UnexpectedRollbackException
        }

        UserEntity reloadedUser = userRepository.findById(traveler.getId()).orElseThrow();
        AnnouncementEntity reloadedAnn = announcementRepository.findById(ann.getId()).orElseThrow();
        assertThat(reloadedUser.getTotalTrips()).isEqualTo(7);
        assertThat(reloadedAnn.isTotalTripsCounted()).isFalse();
    }
}
