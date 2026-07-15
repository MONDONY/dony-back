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

    // Préserve le comportement existant (seul type en usage avant cette feature) —
    // aucun appelant existant à modifier.
    @Query("SELECT d FROM DisputeEntity d WHERE d.bidId = :bidId AND d.type = 'SENDER_NO_SHOW_CONTESTED'")
    Optional<DisputeEntity> findByBidId(@Param("bidId") UUID bidId);

    // Nouveau — idempotence par type pour les litiges d'arrivée.
    Optional<DisputeEntity> findByBidIdAndType(UUID bidId, String type);

    List<DisputeEntity> findBySenderIdOrTravelerIdOrderByCreatedAtDesc(UUID senderId, UUID travelerId);

    @Query("SELECT d FROM DisputeEntity d WHERE (:status IS NULL OR d.status = :status) ORDER BY d.createdAt DESC")
    Page<DisputeEntity> findAdminFiltered(@Param("status") String status, Pageable pageable);

    List<DisputeEntity> findAllByCreatedAtBetweenOrderByCreatedAtAsc(
            java.time.LocalDateTime from, java.time.LocalDateTime to);

    List<DisputeEntity> findAllByResolutionTypeAndResolvedAtBetweenOrderByResolvedAtAsc(
            String resolutionType, java.time.OffsetDateTime from, java.time.OffsetDateTime to);
}
