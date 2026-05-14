package com.dony.api.requests.specification;

import com.dony.api.requests.entity.ParcelSize;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import org.springframework.data.jpa.domain.Specification;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class PackageRequestSpecifications {

    private PackageRequestSpecifications() {}

    public static Specification<PackageRequestEntity> openOnly() {
        return (root, query, cb) -> root.get("status").in(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING);
    }

    public static Specification<PackageRequestEntity> corridor(String departure, String arrival) {
        return (root, query, cb) -> {
            if (departure == null && arrival == null) return cb.conjunction();
            var preds = cb.conjunction();
            if (departure != null) preds = cb.and(preds, cb.equal(cb.lower(root.get("departureCity")), departure.toLowerCase()));
            if (arrival != null) preds = cb.and(preds, cb.equal(cb.lower(root.get("arrivalCity")), arrival.toLowerCase()));
            return preds;
        };
    }

    public static Specification<PackageRequestEntity> dateRange(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            if (from == null && to == null) return cb.conjunction();
            if (from != null && to != null) return cb.between(root.get("desiredDate"), from, to);
            if (from != null) return cb.greaterThanOrEqualTo(root.get("desiredDate"), from);
            return cb.lessThanOrEqualTo(root.get("desiredDate"), to);
        };
    }

    public static Specification<PackageRequestEntity> maxWeight(BigDecimal maxKg) {
        return (root, query, cb) -> maxKg == null ? cb.conjunction()
                : cb.lessThanOrEqualTo(root.get("weightKg"), maxKg);
    }

    public static Specification<PackageRequestEntity> parcelSize(ParcelSize size) {
        return (root, query, cb) -> size == null ? cb.conjunction()
                : cb.equal(root.get("parcelSize"), size);
    }
}
