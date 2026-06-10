package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.matching.dto.TripsSummaryDto;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripsSummaryService {

    private static final List<AnnouncementStatus> ACTIVE_STATUSES = List.of(
            AnnouncementStatus.ACTIVE,
            AnnouncementStatus.FULL,
            AnnouncementStatus.IN_PROGRESS);

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;

    public TripsSummaryService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            PaymentRepository paymentRepository) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
    }

    @Cacheable(cacheNames = "trips-summary", key = "#traveler.id")
    @Transactional(readOnly = true)
    public TripsSummaryDto computeSummary(UserEntity traveler) {
        UUID userId = traveler.getId();
        YearMonth current = YearMonth.now();
        LocalDateTime monthStart = current.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = current.atEndOfMonth().atTime(23, 59, 59);

        long activeTrips = announcementRepository
                .countByTravelerIdAndStatusIn(userId, ACTIVE_STATUSES);

        BigDecimal kgSold = bidRepository.sumDeliveredKgForTraveler(
                userId, BidStatus.COMPLETED, monthStart, monthEnd);

        BigDecimal revenue = paymentRepository.sumCapturedRevenueForTraveler(
                userId, PaymentStatus.RELEASED, monthStart, monthEnd);

        return new TripsSummaryDto(
                activeTrips,
                kgSold != null ? kgSold : BigDecimal.ZERO,
                revenue != null
                        ? revenue.setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);
    }
}
