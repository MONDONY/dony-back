package com.dony.api.cancellation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CancellationRepository extends JpaRepository<CancellationEntity, UUID> {
    List<CancellationEntity> findByCancelledBy(UUID userId);
    long countByCancelledBy(UUID userId);
    java.util.Optional<CancellationEntity> findByBidId(UUID bidId);
    boolean existsByBidIdAndNoShowStatusIn(UUID bidId, List<CancellationStatus> statuses);
}
