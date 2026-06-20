package com.dony.api.alerts;

import com.dony.api.alerts.dto.CorridorAlertRequest;
import com.dony.api.alerts.dto.CorridorAlertResponse;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.matching.dto.MatchingRequestDto;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AlertService {

    private static final int MAX_ACTIVE_ALERTS = 20;

    private final CorridorAlertRepository alertRepository;
    private final UserRepository userRepository;
    private final PackageRequestRepository packageRequestRepository;

    public AlertService(CorridorAlertRepository alertRepository,
                        UserRepository userRepository,
                        PackageRequestRepository packageRequestRepository) {
        this.alertRepository = alertRepository;
        this.userRepository = userRepository;
        this.packageRequestRepository = packageRequestRepository;
    }

    private UUID travelerId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyNotFoundException("Traveler not found"))
                .getId();
    }

    public CorridorAlertResponse create(String firebaseUid, CorridorAlertRequest req) {
        UUID tid = travelerId(firebaseUid);

        if (alertRepository.countByTravelerId(tid) >= MAX_ACTIVE_ALERTS) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "alert-limit-reached",
                    "Alert Limit Reached",
                    "Vous avez atteint la limite de " + MAX_ACTIVE_ALERTS + " alertes.");
        }

        List<String> categories = req.contentCategories() != null
                ? new ArrayList<>(req.contentCategories())
                : new ArrayList<>();

        boolean duplicate = alertRepository.findAllByTravelerId(tid).stream()
                .anyMatch(existing -> isSameFilters(existing, req, categories));
        if (duplicate) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "alert-duplicate",
                    "Duplicate Alert",
                    "Une alerte identique existe déjà pour ce corridor.");
        }

        CorridorAlertEntity entity = new CorridorAlertEntity();
        entity.setTravelerId(tid);
        entity.setDepartureCity(req.departureCity());
        entity.setDepartureCountryCode(req.departureCountryCode());
        entity.setArrivalCity(req.arrivalCity());
        entity.setArrivalCountryCode(req.arrivalCountryCode());
        entity.setDateFrom(req.dateFrom());
        entity.setDateTo(req.dateTo());
        entity.setMinWeightKg(req.minWeightKg());
        entity.setContentCategories(categories);
        entity.setActive(true);

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
        UUID tid = travelerId(firebaseUid);
        return alertRepository.findAllByTravelerId(tid).stream()
                .map(a -> toResponse(a, countMatches(a)))
                .toList();
    }

    public CorridorAlertResponse update(String firebaseUid, UUID alertId,
                                        CorridorAlertRequest req, Boolean active) {
        UUID tid = travelerId(firebaseUid);
        CorridorAlertEntity entity = ownedAlert(tid, alertId);
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
        UUID tid = travelerId(firebaseUid);
        CorridorAlertEntity entity = ownedAlert(tid, alertId);
        entity.softDelete();
        alertRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<MatchingRequestDto> getMatches(String firebaseUid, UUID alertId) {
        UUID tid = travelerId(firebaseUid);
        CorridorAlertEntity alert = ownedAlert(tid, alertId);
        return findMatches(alert).stream()
                .map(this::toMatchingDto)
                .flatMap(Optional::stream)
                .toList();
    }

    private CorridorAlertEntity ownedAlert(UUID tid, UUID alertId) {
        CorridorAlertEntity entity = alertRepository.findById(alertId)
                .orElseThrow(() -> new DonyNotFoundException("Alert not found"));
        if (!entity.getTravelerId().equals(tid)) {
            throw new DonyNotFoundException("Alert not found");
        }
        return entity;
    }

    private long countMatches(CorridorAlertEntity alert) {
        return findMatches(alert).size();
    }

    public List<PackageRequestEntity> findRecentMatches(CorridorAlertEntity alert, java.time.LocalDateTime since) {
        return findMatches(alert).stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(since))
                .toList();
    }

    private List<PackageRequestEntity> findMatches(CorridorAlertEntity alert) {
        return packageRequestRepository
                .findOpenByCorridor(alert.getDepartureCity(), alert.getArrivalCity())
                .stream()
                .filter(p -> fitsDate(p, alert))
                .filter(p -> fitsWeight(p, alert))
                .filter(p -> fitsCategory(p, alert))
                .toList();
    }

    private boolean fitsDate(PackageRequestEntity p, CorridorAlertEntity alert) {
        if (alert.getDateFrom() != null && p.getDesiredDate().isBefore(alert.getDateFrom())) {
            return false;
        }
        return alert.getDateTo() == null || !p.getDesiredDate().isAfter(alert.getDateTo());
    }

    private boolean fitsWeight(PackageRequestEntity p, CorridorAlertEntity alert) {
        return alert.getMinWeightKg() == null
                || p.getWeightKg().compareTo(alert.getMinWeightKg()) >= 0;
    }

    private boolean fitsCategory(PackageRequestEntity p, CorridorAlertEntity alert) {
        List<String> wanted = alert.getContentCategories();
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        return wanted.stream().anyMatch(c -> c.equalsIgnoreCase(p.getContentCategory()));
    }

    private Optional<MatchingRequestDto> toMatchingDto(PackageRequestEntity p) {
        Optional<UserEntity> senderOpt = userRepository.findById(p.getSenderId());
        if (senderOpt.isEmpty()) {
            return Optional.empty();
        }
        UserEntity sender = senderOpt.get();
        String corridor = p.getDepartureCity() + " → " + p.getArrivalCity();
        String senderName = buildName(sender);
        String senderInitials = buildInitials(sender);
        double senderRating = sender.getAverageRating() != null
                ? sender.getAverageRating().doubleValue() : 0.0;
        return Optional.of(new MatchingRequestDto(
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
                truncate(p.getDescription(), 100),
                0,
                p.getCreatedAt() != null ? p.getCreatedAt().toString() : null));
    }

    private String buildName(UserEntity u) {
        String first = u.getFirstName() != null ? u.getFirstName() : "";
        String last = u.getLastName() != null ? u.getLastName() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? "Expéditeur" : full;
    }

    private String buildInitials(UserEntity u) {
        String first = u.getFirstName();
        String last = u.getLastName();
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) sb.append(Character.toUpperCase(first.charAt(0)));
        if (last != null && !last.isBlank()) sb.append(Character.toUpperCase(last.charAt(0)));
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
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
