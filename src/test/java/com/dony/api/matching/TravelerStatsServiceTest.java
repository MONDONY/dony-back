package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.matching.dto.TravelerStatsDto;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TravelerStatsServiceTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock PaymentRepository paymentRepository;

    private TravelerStatsService service() {
        return new TravelerStatsService(announcementRepository, bidRepository, paymentRepository);
    }

    private UserEntity traveler(UUID id) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "averageRating", new BigDecimal("5.00"));
        ReflectionTestUtils.setField(u, "ratingCount", 7);
        return u;
    }

    @Test
    void computeStats_acceptanceRate_countsAcceptedThenDeliveredBids() {
        UUID id = UUID.randomUUID();
        UserEntity traveler = traveler(id);

        when(paymentRepository.sumCapturedRevenueForTraveler(eq(id), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumTotalCapturedRevenueForTraveler(eq(id), eq(PaymentStatus.RELEASED)))
                .thenReturn(new BigDecimal("402.00"));
        when(announcementRepository.countByTravelerIdAndStatusAndCreatedAtBetween(
                eq(id), eq(AnnouncementStatus.COMPLETED), any(), any())).thenReturn(1L);
        when(bidRepository.countDeliveredBidsForTraveler(eq(id), eq(BidStatus.COMPLETED), any(), any()))
                .thenReturn(1L);

        // Acceptation : 4 bids acceptés-ou-au-delà, 2 rejetés → 4/6 = 0,67 (et non 0 %).
        when(bidRepository.countByAnnouncementTravelerIdAndStatusIn(id, BidStatus.ACCEPTED_OR_BEYOND))
                .thenReturn(4L);
        when(bidRepository.countByAnnouncementTravelerIdAndStatus(id, BidStatus.REJECTED)).thenReturn(2L);

        // Agrégats tout-temps.
        when(announcementRepository.countByTravelerIdAndStatus(id, AnnouncementStatus.COMPLETED)).thenReturn(7L);
        when(announcementRepository.countByTravelerIdAndStatusIn(
                eq(id), eq(List.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL, AnnouncementStatus.IN_PROGRESS))))
                .thenReturn(1L);
        when(bidRepository.countByAnnouncementTravelerIdAndStatus(id, BidStatus.COMPLETED)).thenReturn(3L);
        when(bidRepository.countByAnnouncementTravelerIdAndStatusIn(id, BidStatus.EN_ROUTE)).thenReturn(1L);
        when(announcementRepository.findTopDestinationsForTraveler(eq(id), any())).thenReturn(List.of());

        TravelerStatsDto dto = service().computeStats(traveler);

        assertThat(dto.acceptanceRate()).isEqualTo(0.67, within(0.001));
        assertThat(dto.totalTripsCompleted()).isEqualTo(7);
        assertThat(dto.activeTrips()).isEqualTo(1);
        assertThat(dto.totalParcelsDelivered()).isEqualTo(3);
        assertThat(dto.parcelsInTransit()).isEqualTo(1);
        assertThat(dto.ratingCount()).isEqualTo(7);
        assertThat(dto.totalRevenue()).isEqualByComparingTo("402.00");
    }
}
