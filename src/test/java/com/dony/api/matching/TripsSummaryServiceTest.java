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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
                eq(traveler.getId()),
                eq(List.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL,
                        AnnouncementStatus.IN_PROGRESS)))).thenReturn(3L);
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

    @Test
    void computeSummary_exposes_both_legacy_and_period_fields() {
        when(bidRepository.sumDeliveredKgForTraveler(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("4.0"));
        when(paymentRepository.sumCapturedRevenueForTraveler(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("40.00"));

        TripsSummaryDto dto = service.computeSummary(traveler, "7d");

        // Les clients déployés lisent encore les champs « ThisMonth » : ils
        // doivent porter les mêmes valeurs que les champs de période.
        assertThat(dto.kgSold()).isEqualByComparingTo("4.0");
        assertThat(dto.kgSoldThisMonth()).isEqualByComparingTo("4.0");
        assertThat(dto.revenue()).isEqualByComparingTo("40.00");
        assertThat(dto.revenueThisMonth()).isEqualByComparingTo("40.00");
        assertThat(dto.period()).isEqualTo("7d");
    }

    @Test
    void computeSummary_narrows_the_window_for_shorter_periods() {
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        when(bidRepository.sumDeliveredKgForTraveler(
                any(), any(), from.capture(), any())).thenReturn(BigDecimal.ZERO);

        service.computeSummary(traveler, "7d");
        LocalDateTime sevenDays = from.getValue();

        service.computeSummary(traveler, "12m");
        LocalDateTime twelveMonths = from.getValue();

        assertThat(twelveMonths).isBefore(sevenDays);
        assertThat(sevenDays.toLocalDate()).isEqualTo(LocalDate.now().minusDays(7));
        assertThat(twelveMonths.toLocalDate()).isEqualTo(LocalDate.now().minusMonths(12));
    }

    @Test
    void computeSummary_falls_back_to_default_for_unknown_period() {
        when(bidRepository.sumDeliveredKgForTraveler(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        // Une période inconnue ne doit pas faire échouer la requête : c'est ce
        // qui garde l'app fonctionnelle si le client envoie une valeur nouvelle.
        TripsSummaryDto dto = service.computeSummary(traveler, "bogus");

        assertThat(dto.period()).isEqualTo(TripsSummaryService.DEFAULT_PERIOD);
    }

    @Test
    void computeSummary_counts_trips_published_and_parcels_sent() {
        when(announcementRepository.countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                eq(traveler.getId()), any(), any(), eq(AnnouncementStatus.DRAFT)))
                .thenReturn(2L);
        when(bidRepository.countParcelsSentBySender(
                eq(traveler.getId()), any(), any(), any())).thenReturn(5L);

        TripsSummaryDto dto = service.computeSummary(traveler, "30d");

        assertThat(dto.tripsPublished()).isEqualTo(2);
        assertThat(dto.parcelsSent()).isEqualTo(5);
    }
}
