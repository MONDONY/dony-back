package com.dony.api.requests.repository;

import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PackageRequestRepository
        extends JpaRepository<PackageRequestEntity, UUID>, JpaSpecificationExecutor<PackageRequestEntity> {

    Page<PackageRequestEntity> findBySenderIdOrderByCreatedAtDesc(UUID senderId, Pageable pageable);

    long countBySenderIdAndStatusIn(UUID senderId, List<PackageRequestStatus> statuses);

    @Query("""
        SELECT p FROM PackageRequestEntity p
        WHERE p.status IN ('OPEN', 'NEGOTIATING')
          AND p.desiredDate < :cutoffDate
    """)
    List<PackageRequestEntity> findExpired(@Param("cutoffDate") LocalDate cutoffDate);

    @Query("""
        SELECT p FROM PackageRequestEntity p
        WHERE LOWER(p.departureCity) = LOWER(:departureCity)
          AND LOWER(p.arrivalCity) = LOWER(:arrivalCity)
          AND p.status = 'OPEN'
    """)
    List<PackageRequestEntity> findOpenByCorridor(
            @Param("departureCity") String departureCity,
            @Param("arrivalCity") String arrivalCity);
}
