package com.dony.api.disputes;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<DisputeEntity, UUID> {
    Optional<DisputeEntity> findByBidId(UUID bidId);

    List<DisputeEntity> findByTravelerIdOrderByCreatedAtDesc(UUID travelerId);

    @Query("SELECT d FROM DisputeEntity d WHERE (:status IS NULL OR d.status = :status) ORDER BY d.createdAt DESC")
    Page<DisputeEntity> findAdminFiltered(@Param("status") String status, Pageable pageable);
}
