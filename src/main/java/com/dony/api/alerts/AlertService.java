package com.dony.api.alerts;

import com.dony.api.alerts.dto.AlertTripMatchDto;
import com.dony.api.alerts.dto.CorridorAlertRequest;
import com.dony.api.alerts.dto.CorridorAlertResponse;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.common.MatchingTextUtil;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.dto.MatchingRequestDto;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class AlertService {

    private static final int MAX_ACTIVE_ALERTS = 20;

    private final CorridorAlertRepository alertRepository;
    private final UserRepository userRepository;
    private final PackageRequestRepository packageRequestRepository;
    private final AnnouncementRepository announcementRepository;

    public AlertService(CorridorAlertRepository alertRepository,
                        UserRepository userRepository,
                        PackageRequestRepository packageRequestRepository,
                        AnnouncementRepository announcementRepository) {
        this.alertRepository = alertRepository;
        this.userRepository = userRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.announcementRepository = announcementRepository;
    }

    private UUID ownerId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyNotFoundException("User not found"))
                .getId();
    }

    // Item 4: single findAllByOwnerId call for both cap check and duplicate check
    public CorridorAlertResponse create(String firebaseUid, CorridorAlertRequest req) {
        UUID oid = ownerId(firebaseUid);

        List<CorridorAlertEntity> existing = alertRepository.findAllByOwnerId(oid);

        if (existing.size() >= MAX_ACTIVE_ALERTS) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "alert-limit-reached",
                    "Alert Limit Reached",
                    "Vous avez atteint la limite de " + MAX_ACTIVE_ALERTS + " alertes.");
        }

        List<String> categories = req.contentCategories() != null
                ? new ArrayList<>(req.contentCategories())
                : new ArrayList<>();

        boolean duplicate = existing.stream()
                .anyMatch(e -> isSameFilters(e, req, categories));
        if (duplicate) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "alert-duplicate",
                    "Duplicate Alert",
                    "Une alerte identique existe déjà pour ce corridor.");
        }

        CorridorAlertEntity entity = new CorridorAlertEntity();
        entity.setOwnerId(oid);
        entity.setDepartureCity(req.departureCity());
        entity.setDepartureCountryCode(req.departureCountryCode());
        entity.setArrivalCity(req.arrivalCity());
        entity.setArrivalCountryCode(req.arrivalCountryCode());
        entity.setDateFrom(req.dateFrom());
        entity.setDateTo(req.dateTo());
        entity.setMinWeightKg(req.minWeightKg());
        entity.setContentCategories(categories);
        entity.setActive(true);
        entity.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);

        CorridorAlertEntity saved = alertRepository.save(entity);
        return toResponse(saved, 0L);
    }

    private boolean isSameFilters(CorridorAlertEntity e, CorridorAlertRequest req, List<String> categories) {
        return e.getDepartureCity().equalsIgnoreCase(req.departureCity())
                && e.getArrivalCity().equalsIgnoreCase(req.arrivalCity())
                && Objects.equals(e.getDateFrom(), req.dateFrom())
                && Objects.equals(e.getDateTo(), req.dateTo())
                && sameWeight(e.getMinWeightKg(), req.minWeightKg())
                && sameCategories(e.getContentCategories(), categories);
    }

    private boolean sameWeight(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    private boolean sameCategories(List<String> a, List<String> b) {
        List<String> normA = a != null ? a : List.of();
        List<String> normB = b != null ? b : List.of();
        return new HashSet<>(normA).equals(new HashSet<>(normB));
    }

    @Transactional(readOnly = true)
    public List<CorridorAlertResponse> list(String firebaseUid) {
        UUID oid = ownerId(firebaseUid);
        return alertRepository.findAllByOwnerId(oid).stream()
                .map(a -> toResponse(a, countMatches(a)))
                .toList();
    }

    public CorridorAlertResponse update(String firebaseUid, UUID alertId,
                                        CorridorAlertRequest req, Boolean active) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity entity = ownedAlert(oid, alertId);
        entity.setDepartureCity(req.departureCity());
        entity.setDepartureCountryCode(req.departureCountryCode());
        entity.setArrivalCity(req.arrivalCity());
        entity.setArrivalCountryCode(req.arrivalCountryCode());
        entity.setDateFrom(req.dateFrom());
        entity.setDateTo(req.dateTo());
        entity.setMinWeightKg(req.minWeightKg());
        entity.setContentCategories(req.contentCategories() != null
                ? new ArrayList<>(req.contentCategories()) : new ArrayList<>());
        if (active != null) {
            entity.setActive(active);
        }
        CorridorAlertEntity saved = alertRepository.save(entity);
        return toResponse(saved, countMatches(saved));
    }

    public void delete(String firebaseUid, UUID alertId) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity entity = ownedAlert(oid, alertId);
        entity.softDelete();
        alertRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<MatchingRequestDto> getMatches(String firebaseUid, UUID alertId) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity alert = ownedAlert(oid, alertId);
        return toMatchingDtos(findMatchingPackages(alert));
    }

    @Transactional(readOnly = true)
    public List<AlertTripMatchDto> getTripMatches(String firebaseUid, UUID alertId) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity alert = ownedAlert(oid, alertId);
        return toTripDtos(findMatchingTrips(alert));
    }

    private CorridorAlertEntity ownedAlert(UUID oid, UUID alertId) {
        CorridorAlertEntity entity = alertRepository.findById(alertId)
                .orElseThrow(() -> new DonyNotFoundException("Alert not found"));
        if (!entity.getOwnerId().equals(oid)) {
            throw new DonyNotFoundException("Alert not found");
        }
        return entity;
    }

    private long countMatches(CorridorAlertEntity alert) {
        return alert.getDirection() == AlertDirection.SENDER_WANTS_TRIPS
                ? findMatchingTrips(alert).size()
                : findMatchingPackages(alert).size();
    }

    public List<PackageRequestEntity> findRecentMatches(CorridorAlertEntity alert, java.time.LocalDateTime since) {
        return findMatchingPackages(alert).stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(since))
                .toList();
    }

    public List<AnnouncementEntity> findRecentTripMatches(CorridorAlertEntity alert, java.time.LocalDateTime since) {
        return findMatchingTrips(alert).stream()
                .filter(a -> a.getCreatedAt() != null && a.getCreatedAt().isAfter(since))
                .toList();
    }

    private List<PackageRequestEntity> findMatchingPackages(CorridorAlertEntity alert) {
        return packageRequestRepository
                .findOpenByCorridor(alert.getDepartureCity(), alert.getArrivalCity())
                .stream()
                .filter(p -> fitsAlertDate(p.getDesiredDate(), alert))
                .filter(p -> fitsAlertWeight(p, alert))
                .filter(p -> fitsAlertCategory(p, alert))
                .toList();
    }

    private List<AnnouncementEntity> findMatchingTrips(CorridorAlertEntity alert) {
        return announcementRepository
                .findActiveByCorridor(alert.getDepartureCity(), alert.getArrivalCity())
                .stream()
                .filter(a -> fitsAlertDate(a.getDepartureDate(), alert))
                .toList();
    }

    private boolean fitsAlertDate(LocalDate date, CorridorAlertEntity alert) {
        if (alert.getDateFrom() != null && date.isBefore(alert.getDateFrom())) {
            return false;
        }
        return alert.getDateTo() == null || !date.isAfter(alert.getDateTo());
    }

    private List<AlertTripMatchDto> toTripDtos(List<AnnouncementEntity> trips) {
        List<UUID> travelerIds = trips.stream()
                .map(AnnouncementEntity::getTravelerId).distinct().toList();
        Map<UUID, UserEntity> travelerMap = userRepository.findAllById(travelerIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        List<AlertTripMatchDto> result = new ArrayList<>();
        for (AnnouncementEntity a : trips) {
            UserEntity traveler = travelerMap.get(a.getTravelerId());
            if (traveler == null) continue;
            double rating = traveler.getAverageRating() != null
                    ? traveler.getAverageRating().doubleValue() : 0.0;
            result.add(new AlertTripMatchDto(
                    a.getId(), a.getDepartureCity(), a.getArrivalCity(), a.getDepartureDate(),
                    traveler.getId(), MatchingTextUtil.buildName(traveler),
                    MatchingTextUtil.buildInitials(traveler), rating,
                    a.getAvailableKg(), a.getPricePerKg(), a.getTransportMode(), null));
        }
        return result;
    }

    // Item 6: renamed from fitsWeight
    private boolean fitsAlertWeight(PackageRequestEntity p, CorridorAlertEntity alert) {
        return alert.getMinWeightKg() == null
                || p.getWeightKg().compareTo(alert.getMinWeightKg()) >= 0;
    }

    // Item 6: renamed from fitsCategory
    private boolean fitsAlertCategory(PackageRequestEntity p, CorridorAlertEntity alert) {
        List<String> wanted = alert.getContentCategories();
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        return wanted.stream().anyMatch(c -> c.equalsIgnoreCase(p.getContentCategory()));
    }

    // Item 5: batch sender lookup — collect distinct sender IDs, single findAllById call
    private List<MatchingRequestDto> toMatchingDtos(List<PackageRequestEntity> packages) {
        List<UUID> senderIds = packages.stream()
                .map(PackageRequestEntity::getSenderId)
                .distinct()
                .toList();

        Map<UUID, UserEntity> senderMap = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

        List<MatchingRequestDto> result = new ArrayList<>();
        for (PackageRequestEntity p : packages) {
            UserEntity sender = senderMap.get(p.getSenderId());
            if (sender == null) {
                // preserve existing "skip match if sender missing" behavior
                continue;
            }
            result.add(toMatchingDto(p, sender));
        }
        return result;
    }

    // Item 1: use MatchingTextUtil helpers (corridorLabel, buildName, buildInitials, truncate)
    private MatchingRequestDto toMatchingDto(PackageRequestEntity p, UserEntity sender) {
        String corridor = MatchingTextUtil.corridorLabel(p.getDepartureCity(), p.getArrivalCity());
        String senderName = MatchingTextUtil.buildName(sender);
        String senderInitials = MatchingTextUtil.buildInitials(sender);
        double senderRating = sender.getAverageRating() != null
                ? sender.getAverageRating().doubleValue() : 0.0;
        return new MatchingRequestDto(
                p.getId().toString(),
                null,
                corridor,
                p.getDesiredDate().toString(),
                0.0,
                sender.getId() != null ? sender.getId().toString() : null,
                senderName,
                senderInitials,
                senderRating,
                sender.getTotalShipments(),
                p.getWeightKg().doubleValue(),
                p.getContentCategory(),
                0.0,
                p.getPhotoUrl(),
                MatchingTextUtil.truncate(p.getDescription(), 100),
                0,
                p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
    }

    private CorridorAlertResponse toResponse(CorridorAlertEntity e, long matchCount) {
        return new CorridorAlertResponse(
                e.getId(),
                e.getDepartureCity(),
                e.getArrivalCity(),
                e.getDepartureCountryCode(),
                e.getArrivalCountryCode(),
                e.getDateFrom(),
                e.getDateTo(),
                e.getMinWeightKg(),
                new ArrayList<>(e.getContentCategories()),
                e.isActive(),
                matchCount,
                e.getCreatedAt());
    }
}
