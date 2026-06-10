package com.dony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.UserStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TripsSummaryRepositoryIT {

    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;

    @Test
    void countByTravelerIdAndStatusIn_counts_active_full_and_in_progress() {
        UUID travelerId = persistTraveler().getId();
        persistAnnouncement(travelerId, AnnouncementStatus.ACTIVE);
        persistAnnouncement(travelerId, AnnouncementStatus.FULL);
        persistAnnouncement(travelerId, AnnouncementStatus.IN_PROGRESS);
        persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);

        long count = announcementRepository.countByTravelerIdAndStatusIn(
                travelerId,
                List.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL,
                        AnnouncementStatus.IN_PROGRESS));

        assertThat(count).isEqualTo(3);
    }

    @Test
    void sumDeliveredKgForTraveler_sums_completed_bids_weight_in_period() {
        UUID travelerId = persistTraveler().getId();
        AnnouncementEntity ann =
                persistAnnouncement(travelerId, AnnouncementStatus.COMPLETED);
        persistBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("4.50"));
        persistBid(ann.getId(), BidStatus.COMPLETED, new BigDecimal("2.50"));
        persistBid(ann.getId(), BidStatus.CANCELLED, new BigDecimal("9.00"));

        BigDecimal sum = bidRepository.sumDeliveredKgForTraveler(
                travelerId, BidStatus.COMPLETED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(sum).isEqualByComparingTo("7.00");
    }

    @Test
    void sumDeliveredKgForTraveler_returns_zero_when_no_bids() {
        UUID travelerId = persistTraveler().getId();

        BigDecimal sum = bidRepository.sumDeliveredKgForTraveler(
                travelerId, BidStatus.COMPLETED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(sum).isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------
    // Helpers (mirrored from TravelerStatsListenerIT / adapted)
    // -------------------------------------------------------------------------

    private UserEntity persistTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setPhoneNumber("+33" + System.nanoTime());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId, AnnouncementStatus status) {
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
        a.setStatus(status);
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(UUID announcementId, BidStatus status, BigDecimal weightKg) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(weightKg);
        bid.setDeclaredValueEur(new BigDecimal("100.00"));
        bid.setStatus(status);
        return bidRepository.save(bid);
    }
}
