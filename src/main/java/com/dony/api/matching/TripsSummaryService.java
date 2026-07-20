package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.matching.dto.TripsSummaryDto;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripsSummaryService {

    /** Période par défaut si le client n'en demande aucune. */
    public static final String DEFAULT_PERIOD = "30d";

    /** Périodes acceptées. Toute autre valeur retombe sur {@link #DEFAULT_PERIOD}. */
    public static final List<String> SUPPORTED_PERIODS = List.of("7d", "30d", "12m");

    private static final List<AnnouncementStatus> ACTIVE_STATUSES = List.of(
            AnnouncementStatus.ACTIVE,
            AnnouncementStatus.FULL,
            AnnouncementStatus.IN_PROGRESS);

    /** Bids qui ne sont jamais devenus un envoi réel — exclus du compte « colis envoyés ». */
    private static final List<BidStatus> NON_SHIPMENT_STATUSES = List.of(
            BidStatus.AWAITING_PAYMENT,
            BidStatus.REJECTED,
            BidStatus.CANCELLED,
            BidStatus.EXPIRED);

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

    /** Résumé sur la période par défaut. Conservé pour les appelants existants. */
    public TripsSummaryDto computeSummary(UserEntity traveler) {
        return computeSummary(traveler, DEFAULT_PERIOD);
    }

    @Cacheable(cacheNames = "trips-summary", key = "#traveler.id + '-' + #period")
    @Transactional(readOnly = true)
    public TripsSummaryDto computeSummary(UserEntity traveler, String period) {
        UUID userId = traveler.getId();
        String resolved = normalizePeriod(period);
        LocalDateTime from = startOf(resolved);
        LocalDateTime to = LocalDateTime.now();

        // activeTrips est un état courant, pas une mesure de période : il ne
        // dépend pas de l'intervalle demandé.
        long activeTrips = announcementRepository
                .countByTravelerIdAndStatusIn(userId, ACTIVE_STATUSES);

        BigDecimal kgSold = bidRepository.sumDeliveredKgForTraveler(
                userId, BidStatus.COMPLETED, from, to);

        BigDecimal revenue = paymentRepository.sumCapturedRevenueForTraveler(
                userId, PaymentStatus.RELEASED, from, to);

        long tripsPublished = announcementRepository
                .countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                        userId, from, to, AnnouncementStatus.DRAFT);

        long parcelsSent = bidRepository.countParcelsSentBySender(
                userId, from, to, NON_SHIPMENT_STATUSES);

        return TripsSummaryDto.of(
                activeTrips,
                kgSold != null ? kgSold : BigDecimal.ZERO,
                revenue != null
                        ? revenue.setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO,
                tripsPublished,
                parcelsSent,
                resolved);
    }

    /** Une valeur inconnue ne doit pas produire d'erreur : on retombe sur le défaut. */
    private static String normalizePeriod(String period) {
        return period != null && SUPPORTED_PERIODS.contains(period)
                ? period
                : DEFAULT_PERIOD;
    }

    private static LocalDateTime startOf(String period) {
        LocalDate today = LocalDate.now();
        LocalDate start = switch (period) {
            case "7d" -> today.minusDays(7);
            case "12m" -> today.minusMonths(12);
            default -> today.minusDays(30);
        };
        return start.atStartOfDay();
    }

    /**
     * Invalide le résumé caché d'un voyageur. À appeler dès que ses kg livrés ou
     * son escrow libéré changent (livraison confirmée, paiement libéré) pour que
     * les statistiques se rafraîchissent sans attendre le TTL Caffeine (5 min).
     *
     * <p>La clé de cache inclut la période : il faut évincer les trois entrées,
     * une par période supportée. No-op : tout le travail est fait par
     * {@code @CacheEvict} via le proxy Spring (donc appelé depuis un autre bean).
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "trips-summary", key = "#travelerId + '-7d'"),
            @CacheEvict(cacheNames = "trips-summary", key = "#travelerId + '-30d'"),
            @CacheEvict(cacheNames = "trips-summary", key = "#travelerId + '-12m'")
    })
    public void evictSummary(UUID travelerId) {
        // Intentionnellement vide.
    }
}
