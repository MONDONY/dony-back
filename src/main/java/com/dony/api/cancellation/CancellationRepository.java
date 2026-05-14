package com.dony.api.cancellation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CancellationRepository extends JpaRepository<CancellationEntity, UUID> {
    List<CancellationEntity> findByCancelledBy(UUID userId);
    long countByCancelledBy(UUID userId);
    java.util.Optional<CancellationEntity> findByBidId(UUID bidId);
    boolean existsByBidIdAndNoShowStatusIn(UUID bidId, List<CancellationStatus> statuses);

    @Query("SELECT c FROM CancellationEntity c WHERE c.noShowStatus = 'PENDING_CONFIRMATION' " +
           "AND c.contestationDeadline < :now")
    List<CancellationEntity> findExpiredPending(@Param("now") OffsetDateTime now);
}
