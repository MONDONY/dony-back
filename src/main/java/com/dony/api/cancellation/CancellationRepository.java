package com.dony.api.cancellation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CancellationRepository extends JpaRepository<CancellationEntity, UUID> {
    List<CancellationEntity> findByCancelledBy(UUID userId);
    long countByCancelledBy(UUID userId);

    // ── Scope HANDOVER implicite — préserve le comportement et les signatures
    // existantes (aucun appelant, production ou test, n'a besoin de changer). ──

    @Query("SELECT c FROM CancellationEntity c WHERE c.bidId = :bidId AND c.scope = 'HANDOVER'")
    Optional<CancellationEntity> findByBidId(@Param("bidId") UUID bidId);

    @Query("SELECT COUNT(c) > 0 FROM CancellationEntity c WHERE c.bidId = :bidId " +
           "AND c.scope = 'HANDOVER' AND c.noShowStatus IN :statuses")
    boolean existsByBidIdAndNoShowStatusIn(@Param("bidId") UUID bidId,
                                           @Param("statuses") List<CancellationStatus> statuses);

    @Query("SELECT c FROM CancellationEntity c WHERE c.scope = 'HANDOVER' " +
           "AND c.noShowStatus = 'PENDING_CONFIRMATION' AND c.contestationDeadline < :now")
    List<CancellationEntity> findExpiredPending(@Param("now") OffsetDateTime now);

    // ── Scope explicite — nouveau, utilisé par le flux DELIVERY. ──

    Optional<CancellationEntity> findByBidIdAndScope(UUID bidId, CancellationScope scope);

    boolean existsByBidIdAndScopeAndNoShowStatusIn(UUID bidId, CancellationScope scope,
                                                    List<CancellationStatus> statuses);

    @Query("SELECT c FROM CancellationEntity c WHERE c.scope = :scope " +
           "AND c.noShowStatus = 'PENDING_CONFIRMATION' AND c.contestationDeadline < :now")
    List<CancellationEntity> findExpiredPendingByScope(@Param("scope") CancellationScope scope,
                                                        @Param("now") OffsetDateTime now);

    @Query("SELECT c FROM CancellationEntity c WHERE (:noShowStatus IS NULL OR c.noShowStatus = :noShowStatus)")
    Page<CancellationEntity> findAdminFiltered(@Param("noShowStatus") CancellationStatus noShowStatus, Pageable pageable);
}
