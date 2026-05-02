package com.dony.api.matching;

import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

public class AnnouncementSpecification {

    public static Specification<AnnouncementEntity> hasStatus(AnnouncementStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<AnnouncementEntity> hasDepartureCity(String city) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("departureCity")), city.toLowerCase());
    }

    public static Specification<AnnouncementEntity> hasArrivalCity(String city) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("arrivalCity")), city.toLowerCase());
    }

    public static Specification<AnnouncementEntity> departureDateFrom(LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("departureDate"), from);
    }

    public static Specification<AnnouncementEntity> departureDateTo(LocalDate to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("departureDate"), to);
    }

    public static Specification<AnnouncementEntity> minAvailableKg(BigDecimal kg) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("availableKg"), kg);
    }

    public static Specification<AnnouncementEntity> idIn(Collection<UUID> ids) {
        return (root, query, cb) -> {
            if (ids == null || ids.isEmpty()) return cb.disjunction(); // always false → 0 rows
            return root.get("id").in(ids);
        };
    }

    public static Specification<AnnouncementEntity> hasPickupCoordinates() {
        return (root, query, cb) -> cb.and(
            cb.isNotNull(root.get("pickupLat")),
            cb.isNotNull(root.get("pickupLng"))
        );
    }
}
