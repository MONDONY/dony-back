package com.dony.api.matching;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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

    public static Specification<AnnouncementEntity> maxAvailableKg(BigDecimal kg) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("availableKg"), kg);
    }

    public static Specification<AnnouncementEntity> maxPricePerKg(BigDecimal max) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("pricePerKg"), max);
    }

    /**
     * Filters announcements whose departure date falls on a Saturday (dow=6) or Sunday (dow=0).
     * Uses PostgreSQL date_part('dow', ...) function.
     */
    public static Specification<AnnouncementEntity> weekendOnly() {
        return (root, query, cb) -> {
            Expression<Double> dow = cb.function("date_part", Double.class,
                    cb.literal("dow"), root.get("departureDate"));
            return cb.or(cb.equal(dow, 0.0), cb.equal(dow, 6.0));
        };
    }

    /**
     * Filters announcements to travelers whose averageRating >= minRating.
     * Uses a subquery on users table to avoid cross-package service injection.
     */
    public static Specification<AnnouncementEntity> minRating(BigDecimal minRating) {
        return (root, query, cb) -> {
            Subquery<UUID> sq = query.subquery(UUID.class);
            Root<UserEntity> user = sq.from(UserEntity.class);
            sq.select(user.<UUID>get("id"))
              .where(cb.greaterThanOrEqualTo(user.get("averageRating"), minRating));
            return root.get("travelerId").in(sq);
        };
    }

    /**
     * Filters announcements to Kilo Pro travelers only.
     * Uses a subquery on users table to avoid cross-package service injection.
     */
    public static Specification<AnnouncementEntity> kiloProOnly() {
        return (root, query, cb) -> {
            Subquery<UUID> sq = query.subquery(UUID.class);
            Root<UserEntity> user = sq.from(UserEntity.class);
            sq.select(user.<UUID>get("id"))
              .where(cb.isTrue(user.get("kiloPro")));
            return root.get("travelerId").in(sq);
        };
    }

    public static Specification<AnnouncementEntity> hasTransportMode(TransportMode mode) {
        return (root, query, cb) -> cb.equal(root.get("transportMode"), mode);
    }

    /**
     * Filters to travelers who have completed KYC verification.
     * Uses a subquery on users table.
     */
    public static Specification<AnnouncementEntity> kycVerifiedOnly() {
        return (root, query, cb) -> {
            Subquery<UUID> sq = query.subquery(UUID.class);
            Root<UserEntity> user = sq.from(UserEntity.class);
            sq.select(user.<UUID>get("id"))
              .where(cb.equal(user.get("kycStatus"), KycStatus.VERIFIED));
            return root.get("travelerId").in(sq);
        };
    }

    /**
     * Filters announcements that accept a specific content type in their acceptedContentTypes list.
     */
    public static Specification<AnnouncementEntity> hasAcceptedContentType(String contentType) {
        return (root, query, cb) -> cb.isMember(contentType, root.get("acceptedContentTypes"));
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
