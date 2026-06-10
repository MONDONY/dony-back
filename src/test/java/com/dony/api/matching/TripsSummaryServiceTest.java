package com.dony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.dony.api.auth.UserEntity;
import com.dony.api.matching.dto.TripsSummaryDto;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TripsSummaryServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private PaymentRepository paymentRepository;

    private TripsSummaryService service;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        service = new TripsSummaryService(
                announcementRepository, bidRepository, paymentRepository);
        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", UUID.randomUUID());
    }

    @Test
    void computeSummary_aggregates_active_trips_kg_and_revenue() {
        when(announcementRepository.countByTravelerIdAndStatusIn(
                eq(traveler.getId()), any())).thenReturn(3L);
        when(bidRepository.sumDeliveredKgForTraveler(
                eq(traveler.getId()), eq(BidStatus.COMPLETED), any(), any()))
                .thenReturn(new BigDecimal("19.0"));
        when(paymentRepository.sumCapturedRevenueForTraveler(
                eq(traveler.getId()), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(new BigDecimal("152.4567"));

        TripsSummaryDto dto = service.computeSummary(traveler);

        assertThat(dto.activeTrips()).isEqualTo(3);
        assertThat(dto.kgSoldThisMonth()).isEqualByComparingTo("19.0");
        assertThat(dto.revenueThisMonth()).isEqualByComparingTo("152.46");
    }

    @Test
    void computeSummary_returns_zeros_when_repositories_return_null() {
        when(announcementRepository.countByTravelerIdAndStatusIn(
                eq(traveler.getId()), any())).thenReturn(0L);
        when(bidRepository.sumDeliveredKgForTraveler(any(), any(), any(), any()))
                .thenReturn(null);
        when(paymentRepository.sumCapturedRevenueForTraveler(any(), any(), any(), any()))
                .thenReturn(null);

        TripsSummaryDto dto = service.computeSummary(traveler);

        assertThat(dto.activeTrips()).isZero();
        assertThat(dto.kgSoldThisMonth()).isEqualByComparingTo("0");
        assertThat(dto.revenueThisMonth()).isEqualByComparingTo("0");
    }
}
