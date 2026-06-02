package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.config.DonyConfigProperties;
import com.dony.api.matching.dto.ProAnalyticsResponse;
import com.dony.api.matching.dto.ProAnalyticsResponse.KpiDto;
import com.dony.api.matching.dto.ProAnalyticsResponse.TransactionRowDto;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProAnalyticsService {

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final DonyConfigProperties config;

    public ProAnalyticsService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            PaymentRepository paymentRepository,
            DonyConfigProperties config
    ) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public ProAnalyticsResponse computeAnalytics(UserEntity traveler, String period) {
        UUID userId = traveler.getId();
        LocalDateTime[] range = periodRange(period);
        LocalDateTime from = range[0];
        LocalDateTime to = range[1];
        LocalDateTime[] prevRange = previousPeriodRange(period, from);
        LocalDateTime prevFrom = prevRange[0];
        LocalDateTime prevTo = prevRange[1];

        // Revenue
        BigDecimal revenue = orZero(paymentRepository.sumCapturedRevenueForTraveler(
                userId, PaymentStatus.RELEASED, from, to));
        BigDecimal prevRevenue = orZero(paymentRepository.sumCapturedRevenueForTraveler(
                userId, PaymentStatus.RELEASED, prevFrom, prevTo));

        // Trips
        long trips = announcementRepository.countByTravelerIdAndCreatedAtBetween(userId, from, to);
        long prevTrips = announcementRepository.countByTravelerIdAndCreatedAtBetween(userId, prevFrom, prevTo);

        // Parcels delivered
        long parcels = bidRepository.countDeliveredBidsForTraveler(userId, BidStatus.COMPLETED, from, to);

        // Acceptance rate
        long accepted = bidRepository.countByAnnouncementTravelerIdAndStatus(userId, BidStatus.ACCEPTED);
        long rejected = bidRepository.countByAnnouncementTravelerIdAndStatus(userId, BidStatus.REJECTED);
        double acceptRate = (accepted + rejected) == 0 ? 0.0
                : BigDecimal.valueOf((double) accepted / (accepted + rejected))
                        .setScale(2, RoundingMode.HALF_UP).doubleValue();

        // Rating
        BigDecimal rating = traveler.getAverageRating() != null
                ? traveler.getAverageRating().setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // KPIs
        List<KpiDto> kpis = new ArrayList<>();
        kpis.add(revenueKpi(revenue, prevRevenue));
        kpis.add(new KpiDto("trips", "Trajets", String.valueOf(trips), trend(trips, prevTrips), delta(trips, prevTrips)));
        kpis.add(new KpiDto("parcels", "Colis gérés", String.valueOf(parcels), null, null));
        kpis.add(new KpiDto("acceptance", "Taux acceptation", formatPercent(acceptRate), null, null));
        kpis.add(new KpiDto("rating", "Note moyenne", rating + "/5", null, null));

        // Transactions
        List<TransactionRowDto> transactions = buildTransactions(userId, from, to);

        return new ProAnalyticsResponse(period, kpis, transactions);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private KpiDto revenueKpi(BigDecimal current, BigDecimal prev) {
        String value = formatEuros(current);
        if (prev.compareTo(BigDecimal.ZERO) == 0) {
            return new KpiDto("revenue", "Revenus nets", value, null, null);
        }
        BigDecimal diff = current.subtract(prev);
        BigDecimal pct = diff.divide(prev, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        String trendDir = diff.compareTo(BigDecimal.ZERO) >= 0 ? "up" : "down";
        String trendVal = (diff.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + pct.setScale(0, RoundingMode.HALF_UP) + "%";
        return new KpiDto("revenue", "Revenus nets", value, trendDir, trendVal);
    }

    private String trend(long current, long prev) {
        if (current > prev) return "up";
        if (current < prev) return "down";
        return "stable";
    }

    private String delta(long current, long prev) {
        long diff = current - prev;
        return (diff >= 0 ? "+" : "") + diff;
    }

    private String formatEuros(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toString() + " €";
    }

    private String formatPercent(double rate) {
        return BigDecimal.valueOf(rate * 100).setScale(0, RoundingMode.HALF_UP) + "%";
    }

    private BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private List<TransactionRowDto> buildTransactions(UUID userId, LocalDateTime from, LocalDateTime to) {
        List<AnnouncementEntity> announcements =
                announcementRepository.findByTravelerIdAndStatusAndCreatedAtBetween(
                        userId, AnnouncementStatus.COMPLETED, from, to);
        List<TransactionRowDto> rows = new ArrayList<>();
        for (AnnouncementEntity ann : announcements) {
            long parcelCount = bidRepository.countByAnnouncementIdAndStatus(ann.getId(), BidStatus.COMPLETED);
            BigDecimal gross = orZero(paymentRepository.sumGrossRevenueForAnnouncement(ann.getId(), PaymentStatus.RELEASED));
            BigDecimal commission = gross.multiply(config.commission().rate()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(commission);
            String corridor = ann.getDepartureCity() + " → " + ann.getArrivalCity();
            rows.add(new TransactionRowDto(
                    ann.getId().toString(),
                    corridor,
                    ann.getDepartureDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    (int) parcelCount,
                    gross.multiply(BigDecimal.valueOf(100)).longValue(),
                    commission.multiply(BigDecimal.valueOf(100)).longValue(),
                    net.multiply(BigDecimal.valueOf(100)).longValue()
            ));
        }
        return rows;
    }

    private LocalDateTime[] periodRange(String period) {
        return switch (period) {
            case "quarter" -> {
                YearMonth current = YearMonth.now();
                int month = current.getMonthValue();
                int quarterStart = ((month - 1) / 3) * 3 + 1;
                LocalDateTime from = LocalDate.of(current.getYear(), quarterStart, 1).atStartOfDay();
                LocalDateTime to = LocalDateTime.now();
                yield new LocalDateTime[]{from, to};
            }
            case "year" -> {
                int year = LocalDate.now().getYear();
                yield new LocalDateTime[]{
                        LocalDate.of(year, 1, 1).atStartOfDay(),
                        LocalDateTime.now()
                };
            }
            default -> { // month
                YearMonth current = YearMonth.now();
                yield new LocalDateTime[]{
                        current.atDay(1).atStartOfDay(),
                        current.atEndOfMonth().atTime(23, 59, 59)
                };
            }
        };
    }

    private LocalDateTime[] previousPeriodRange(String period, LocalDateTime currentFrom) {
        return switch (period) {
            case "quarter" -> {
                LocalDateTime prevFrom = currentFrom.minusMonths(3);
                yield new LocalDateTime[]{prevFrom, currentFrom.minusSeconds(1)};
            }
            case "year" -> {
                LocalDateTime prevFrom = currentFrom.minusYears(1);
                yield new LocalDateTime[]{prevFrom, currentFrom.minusSeconds(1)};
            }
            default -> { // month
                LocalDateTime prevFrom = currentFrom.minusMonths(1);
                yield new LocalDateTime[]{prevFrom, currentFrom.minusSeconds(1)};
            }
        };
    }
}
