package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.MatchingTextUtil;
import com.dony.api.matching.dto.MatchingRequestDto;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class MatchingService {

    private final AnnouncementRepository announcementRepository;
    private final PackageRequestRepository packageRequestRepository;
    private final UserRepository userRepository;

    public MatchingService(
            AnnouncementRepository announcementRepository,
            PackageRequestRepository packageRequestRepository,
            UserRepository userRepository) {
        this.announcementRepository = announcementRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.userRepository = userRepository;
    }

    public List<MatchingRequestDto> findMatchingRequests(UUID travelerId) {
        List<AnnouncementEntity> activeAnnouncements =
                announcementRepository.findActiveByTravelerId(travelerId);

        List<MatchingRequestDto> results = new ArrayList<>();

        for (AnnouncementEntity announcement : activeAnnouncements) {
            List<PackageRequestEntity> candidates = packageRequestRepository
                    .findOpenByCorridor(announcement.getDepartureCity(), announcement.getArrivalCity());

            for (PackageRequestEntity request : candidates) {
                if (!fitsWeight(request, announcement)) continue;
                if (!fitsDate(request, announcement)) continue;

                Optional<UserEntity> senderOpt = userRepository.findById(request.getSenderId());
                if (senderOpt.isEmpty()) continue;

                UserEntity sender = senderOpt.get();
                results.add(toDto(request, announcement, sender));
            }
        }

        results.sort((a, b) -> Integer.compare(b.matchScore(), a.matchScore()));
        return results;
    }

    /**
     * Inverse de {@link #findMatchingRequests} : pour une demande de colis donnée,
     * retourne les ids des voyageurs dont au moins un trajet ACTIVE/FULL matche
     * (même règle corridor + poids + fenêtre de date). Utilisé par la notification
     * temps réel à la création d'une demande. Une demande non OPEN ne matche rien.
     */
    public List<UUID> findTravelersMatchingPackage(UUID requestId) {
        PackageRequestEntity request = packageRequestRepository.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != PackageRequestStatus.OPEN) {
            return List.of();
        }
        return announcementRepository
                .findActiveByCorridor(request.getDepartureCity(), request.getArrivalCity())
                .stream()
                .filter(a -> fitsWeight(request, a))
                .filter(a -> fitsDate(request, a))
                .map(AnnouncementEntity::getTravelerId)
                .distinct()
                .toList();
    }

    private boolean fitsWeight(PackageRequestEntity request, AnnouncementEntity announcement) {
        return request.getWeightKg().compareTo(announcement.getAvailableKg()) <= 0;
    }

    private boolean fitsDate(PackageRequestEntity request, AnnouncementEntity announcement) {
        long daysDiff = Math.abs(ChronoUnit.DAYS.between(
                request.getDesiredDate(), announcement.getDepartureDate()));
        return daysDiff <= request.getDateToleranceDays();
    }

    private MatchingRequestDto toDto(PackageRequestEntity request,
                                     AnnouncementEntity announcement,
                                     UserEntity sender) {
        String corridor = MatchingTextUtil.corridorLabel(announcement.getDepartureCity(), announcement.getArrivalCity());
        String senderName = MatchingTextUtil.buildName(sender);
        String senderInitials = MatchingTextUtil.buildInitials(sender);
        double senderRating = sender.getAverageRating() != null
                ? sender.getAverageRating().doubleValue() : 0.0;

        double budgetPerKg = computeBudgetPerKg(request);
        int matchScore = computeMatchScore(request, announcement, budgetPerKg);
        String messageExcerpt = MatchingTextUtil.truncate(request.getDescription(), 100);

        return new MatchingRequestDto(
                request.getId().toString(),
                announcement.getId().toString(),
                corridor,
                announcement.getDepartureDate().toString(),
                announcement.getAvailableKg().doubleValue(),
                sender.getId().toString(),
                senderName,
                senderInitials,
                senderRating,
                sender.getTotalShipments(),
                request.getWeightKg().doubleValue(),
                request.getContentCategory(),
                budgetPerKg,
                request.getPhotoUrl(),
                messageExcerpt,
                matchScore,
                request.getCreatedAt().toString()
        );
    }

    private int computeMatchScore(PackageRequestEntity request,
                                  AnnouncementEntity announcement,
                                  double budgetPerKg) {
        double ratio = request.getWeightKg().divide(announcement.getAvailableKg(),
                4, java.math.RoundingMode.HALF_UP).doubleValue();
        int weightScore = (int) Math.round((1.0 - Math.min(ratio, 1.0)) * 40);

        double pricePerKg = announcement.getPricePerKg().doubleValue();
        int budgetScore;
        if (budgetPerKg >= pricePerKg) {
            budgetScore = 35;
        } else if (budgetPerKg >= pricePerKg * 0.8) {
            budgetScore = 20;
        } else {
            budgetScore = 5;
        }

        long daysDiff = Math.abs(ChronoUnit.DAYS.between(
                request.getDesiredDate(), announcement.getDepartureDate()));
        int dateScore;
        if (daysDiff <= request.getDateToleranceDays()) {
            dateScore = 25;
        } else if (daysDiff <= request.getDateToleranceDays() + 3L) {
            dateScore = 15;
        } else {
            dateScore = 5;
        }

        return Math.min(100, weightScore + budgetScore + dateScore);
    }

    private double computeBudgetPerKg(PackageRequestEntity request) {
        if (request.getTargetPriceEur() == null
                || request.getWeightKg().compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return request.getTargetPriceEur()
                .divide(request.getWeightKg(), 4, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

}
