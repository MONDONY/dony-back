package com.dony.api.alerts;

import com.dony.api.alerts.dto.CorridorAlertRequest;
import com.dony.api.alerts.dto.CorridorAlertResponse;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
