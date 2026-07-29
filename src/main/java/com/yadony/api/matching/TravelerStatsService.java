package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.dto.TravelerStatsDto;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
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

        // Carte (escrow libéré) + espèces (net des bids CASH livrés, hors PaymentEntity).
        BigDecimal monthlyRevenue = TravelerRevenue.cardPlusCash(
                paymentRepository.sumCapturedRevenueForTraveler(
                        userId, PaymentStatus.RELEASED, monthStart, monthEnd),
                bidRepository.sumCashNetRevenueForTraveler(
                        userId, BidStatus.COMPLETED, PaymentMethod.CASH, monthStart, monthEnd));
        BigDecimal totalRevenue = TravelerRevenue.cardPlusCash(
                paymentRepository.sumTotalCapturedRevenueForTraveler(userId, PaymentStatus.RELEASED),
                bidRepository.sumTotalCashNetRevenueForTraveler(
                        userId, BidStatus.COMPLETED, PaymentMethod.CASH));

        long monthlyTrips = announcementRepository
                .countByTravelerIdAndStatusAndCreatedAtBetween(userId, AnnouncementStatus.COMPLETED, monthStart, monthEnd);

        long deliveredBids = bidRepository
                .countDeliveredBidsForTraveler(userId, BidStatus.COMPLETED, monthStart, monthEnd);

        // Taux d'acceptation : un bid accepté puis livré n'est plus en statut ACCEPTED,
        // on compte donc tout bid ayant dépassé le stade de l'acceptation.
        long accepted = bidRepository.countByAnnouncementTravelerIdAndStatusIn(userId, BidStatus.ACCEPTED_OR_BEYOND);
        // Refus explicites seulement — les bids rejetés par suppression d'annonce ne comptent pas.
        long rejected = bidRepository.countExplicitRejectionsForTraveler(userId);
        double acceptanceRate = (accepted + rejected) == 0 ? 0.0
                : BigDecimal.valueOf((double) accepted / (accepted + rejected))
                        .setScale(2, RoundingMode.HALF_UP).doubleValue();

        // ── Agrégats tout-temps pour la vue d'ensemble du cockpit ──
        long totalTripsCompleted = announcementRepository.countByTravelerIdAndStatus(userId, AnnouncementStatus.COMPLETED);
        // Trajets actifs = mêmes statuts que TripsSummaryService (ACTIVE, FULL, IN_PROGRESS) :
        // un trajet parti avec des colis (IN_PROGRESS) reste « actif » tant qu'il n'est pas COMPLETED.
        long activeTrips = announcementRepository.countByTravelerIdAndStatusIn(
                userId, List.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL, AnnouncementStatus.IN_PROGRESS));
        long totalParcelsDelivered = bidRepository.countByAnnouncementTravelerIdAndStatus(userId, BidStatus.COMPLETED);
        // Colis « en cours » = remis en main OU en transit (pas encore livrés).
        long parcelsInTransit = bidRepository.countByAnnouncementTravelerIdAndStatusIn(userId, BidStatus.EN_ROUTE);

        List<TravelerStatsDto.DestinationStat> topDestinations = announcementRepository
                .findTopDestinationsForTraveler(userId, PageRequest.of(0, 3));

        return new TravelerStatsDto(
                monthlyRevenue != null ? monthlyRevenue.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                totalRevenue != null ? totalRevenue.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                monthlyTrips,
                deliveredBids,
                acceptanceRate,
                traveler.getAverageRating() != null ? traveler.getAverageRating() : BigDecimal.ZERO,
                topDestinations,
                totalTripsCompleted,
                activeTrips,
                totalParcelsDelivered,
                parcelsInTransit,
                traveler.getRatingCount()
        );
    }
}
