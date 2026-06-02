package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
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

    private ProAnalyticsService service() {
        return new ProAnalyticsService(announcementRepository, bidRepository, paymentRepository);
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

        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", annId);
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Dakar");
        ann.setDepartureDate(LocalDate.of(2026, 6, 10));

        lenient().when(paymentRepository.sumCapturedRevenueForTraveler(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(announcementRepository.countByTravelerIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(0L);
        lenient().when(bidRepository.countDeliveredBidsForTraveler(any(), any(), any(), any()))
                .thenReturn(0L);
        lenient().when(bidRepository.countByAnnouncementTravelerIdAndStatus(any(), any()))
                .thenReturn(0L);

        when(announcementRepository.findByTravelerIdAndStatusAndCreatedAtBetween(
                eq(travelerId), eq(AnnouncementStatus.COMPLETED), any(), any()))
                .thenReturn(List.of(ann));
        when(bidRepository.countByAnnouncementIdAndStatus(annId, BidStatus.COMPLETED)).thenReturn(2L);
        when(paymentRepository.sumGrossRevenueForAnnouncement(annId, PaymentStatus.RELEASED))
                .thenReturn(new BigDecimal("100.00"));
        // Commission réellement prélevée = 8,00 € (override 8 %), PAS 12,00 € (taux global).
        when(paymentRepository.sumCommissionForAnnouncement(annId, PaymentStatus.RELEASED))
                .thenReturn(new BigDecimal("8.00"));

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "month");

        ProAnalyticsResponse.TransactionRowDto row = resp.transactions().get(0);
        assertThat(row.grossRevenue()).isEqualTo(10000L);   // 100,00 €
        assertThat(row.commission()).isEqualTo(800L);       // 8,00 € (≠ 1200 au taux global)
        assertThat(row.netRevenue()).isEqualTo(9200L);      // 100 − 8 = 92,00 €
    }

    @Test
    void transactions_emptyWhenNoCompletedAnnouncements() {
        UUID travelerId = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        lenient().when(paymentRepository.sumCapturedRevenueForTraveler(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(announcementRepository.countByTravelerIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(0L);
        lenient().when(bidRepository.countDeliveredBidsForTraveler(any(), any(), any(), any()))
                .thenReturn(0L);
        lenient().when(bidRepository.countByAnnouncementTravelerIdAndStatus(any(), any()))
                .thenReturn(0L);
        when(announcementRepository.findByTravelerIdAndStatusAndCreatedAtBetween(
                eq(travelerId), eq(AnnouncementStatus.COMPLETED), any(), any()))
                .thenReturn(List.of());

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "month");

        assertThat(resp.transactions()).isEmpty();
    }
}
