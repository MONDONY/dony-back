package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.matching.dto.TravelerStatsDto;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Service
public class TravelerStatsService {

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;

    public TravelerStatsService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            PaymentRepository paymentRepository
    ) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public TravelerStatsDto computeStats(UserEntity traveler) {
        UUID userId = traveler.getId();
        YearMonth current = YearMonth.now();
        LocalDateTime monthStart = current.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = current.atEndOfMonth().atTime(23, 59, 59);

        BigDecimal monthlyRevenue = paymentRepository
                .sumCapturedRevenueForTraveler(userId, PaymentStatus.RELEASED, monthStart, monthEnd);
        BigDecimal totalRevenue = paymentRepository
                .sumTotalCapturedRevenueForTraveler(userId, PaymentStatus.RELEASED);

        long monthlyTrips = announcementRepository
                .countByTravelerIdAndStatusAndCreatedAtBetween(userId, AnnouncementStatus.COMPLETED, monthStart, monthEnd);

        long deliveredBids = bidRepository
                .countDeliveredBidsForTraveler(userId, BidStatus.COMPLETED, monthStart, monthEnd);

        long accepted = bidRepository.countByAnnouncementTravelerIdAndStatus(userId, BidStatus.ACCEPTED);
        long rejected = bidRepository.countByAnnouncementTravelerIdAndStatus(userId, BidStatus.REJECTED);
        double acceptanceRate = (accepted + rejected) == 0 ? 0.0
                : BigDecimal.valueOf((double) accepted / (accepted + rejected))
                        .setScale(2, RoundingMode.HALF_UP).doubleValue();

        List<TravelerStatsDto.DestinationStat> topDestinations = announcementRepository
                .findTopDestinationsForTraveler(userId, PageRequest.of(0, 3));

        return new TravelerStatsDto(
                monthlyRevenue != null ? monthlyRevenue.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                totalRevenue != null ? totalRevenue.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                monthlyTrips,
                deliveredBids,
                acceptanceRate,
                traveler.getAverageRating() != null ? traveler.getAverageRating() : BigDecimal.ZERO,
                topDestinations
        );
    }
}
