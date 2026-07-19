package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.matching.dto.AnnouncementRevenueRow;
import com.dony.api.matching.dto.ProAnalyticsResponse;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProAnalyticsServiceTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock CurrencyRegistry currencyRegistry;

    private ProAnalyticsService service() {
        lenient().when(currencyRegistry.minorUnitOf(any())).thenReturn(2);
        return new ProAnalyticsService(announcementRepository, bidRepository, paymentRepository, currencyRegistry);
    }

    /** Stubs communs des KPI non liés aux transactions (revenus/trajets/colis/acceptation). */
    private void stubKpisToZero() {
        lenient().when(paymentRepository.sumCapturedRevenueForTraveler(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(announcementRepository.countByTravelerIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(0L);
        lenient().when(bidRepository.countDeliveredBidsForTraveler(any(), any(), any(), any()))
                .thenReturn(0L);
        lenient().when(bidRepository.countByAnnouncementTravelerIdAndStatus(any(), any()))
                .thenReturn(0L);
    }

    /**
     * La commission affichée doit refléter la commission RÉELLEMENT prélevée
     * (somme des {@code commissionAmount}, overrides inclus), pas un recalcul
     * {@code gross × taux global}.
     */
    @Test
    void transactions_useActualChargedCommission_notGlobalRate() {
        UUID travelerId = UUID.randomUUID();
        UUID annId = UUID.randomUUID();

        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        stubKpisToZero();

        when(paymentRepository.findReleasedRevenueByAnnouncement(
                eq(travelerId), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of(new AnnouncementRevenueRow(
                        annId, "Paris", "Dakar", LocalDate.of(2026, 6, 10),
                        2L, new BigDecimal("100.00"), new BigDecimal("8.00"))));

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "month");

        ProAnalyticsResponse.TransactionRowDto row = resp.transactions().get(0);
        assertThat(row.grossRevenue()).isEqualTo(10000L);   // 100,00 €
        assertThat(row.commission()).isEqualTo(800L);       // 8,00 € (≠ 1200 au taux global)
        assertThat(row.netRevenue()).isEqualTo(9200L);      // 100 − 8 = 92,00 €
        assertThat(row.parcelCount()).isEqualTo(2);
        assertThat(row.corridor()).isEqualTo("Paris → Dakar");
    }

    /**
     * Le détail est piloté par les paiements RELEASED : la somme des lignes Net
     * doit se réconcilier avec le total encaissé (KPI « Revenus nets »).
     */
    @Test
    void transactions_netSumReconcilesWithReleasedPayments() {
        UUID travelerId = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        stubKpisToZero();

        when(paymentRepository.findReleasedRevenueByAnnouncement(
                eq(travelerId), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of(
                        new AnnouncementRevenueRow(UUID.randomUUID(), "Moscow", "Abidjan",
                                LocalDate.of(2026, 6, 29), 1L,
                                new BigDecimal("150.08"), new BigDecimal("16.08")),   // net 134,00
                        new AnnouncementRevenueRow(UUID.randomUUID(), "Paris", "Abidjan",
                                LocalDate.of(2026, 6, 24), 1L,
                                new BigDecimal("300.16"), new BigDecimal("32.16"))    // net 268,00
                ));

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "year");

        long netSum = resp.transactions().stream()
                .mapToLong(ProAnalyticsResponse.TransactionRowDto::netRevenue)
                .sum();
        assertThat(netSum).isEqualTo(40200L); // 134,00 + 268,00 = 402,00 €
        assertThat(resp.transactions()).hasSize(2);
    }

    @Test
    void transactions_emptyWhenNoReleasedPayments() {
        UUID travelerId = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        stubKpisToZero();
        when(paymentRepository.findReleasedRevenueByAnnouncement(
                eq(travelerId), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of());

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "month");

        assertThat(resp.transactions()).isEmpty();
    }
}
