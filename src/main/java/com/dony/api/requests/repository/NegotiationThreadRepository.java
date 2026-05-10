package com.dony.api.requests.repository;

import com.dony.api.requests.entity.NegotiationThreadEntity;
import com.dony.api.requests.entity.NegotiationThreadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NegotiationThreadRepository extends JpaRepository<NegotiationThreadEntity, UUID> {

    /**
     * True iff at least one thread exists for (request, traveler) — regardless of status.
     * Used to assert the user is a participant. Derived count works even when multiple
     * historical threads exist (one active + N terminal), unlike a unique-result Optional.
     */
    boolean existsByPackageRequestIdAndTravelerId(UUID packageRequestId, UUID travelerId);

    /**
     * Active (non-terminal) thread for the (request, traveler) pair.
     * REJECTED / AUTO_REJECTED / EXPIRED threads are intentionally excluded so the
     * traveler can retry with a fresh proposal — see V63 unique-index change.
     */
    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.packageRequestId = :requestId
          AND t.travelerId = :travelerId
          AND t.status IN (
              com.dony.api.requests.entity.NegotiationThreadStatus.OPEN,
              com.dony.api.requests.entity.NegotiationThreadStatus.AWAITING_TRIP,
              com.dony.api.requests.entity.NegotiationThreadStatus.AWAITING_PAYMENT,
              com.dony.api.requests.entity.NegotiationThreadStatus.ACCEPTED
          )
    """)
    Optional<NegotiationThreadEntity> findActiveByPackageRequestIdAndTravelerId(
        @Param("requestId") UUID requestId,
        @Param("travelerId") UUID travelerId
    );

    List<NegotiationThreadEntity> findByPackageRequestId(UUID packageRequestId);

    long countByTravelerIdAndStatus(UUID travelerId, NegotiationThreadStatus status);

    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.status = 'OPEN'
          AND t.lastActivityAt < :cutoff
    """)
    List<NegotiationThreadEntity> findInactive(@Param("cutoff") LocalDateTime cutoff);

    @Query("""
        SELECT count(t) FROM NegotiationThreadEntity t
        WHERE t.travelerId = :travelerId AND t.createdAt > :since
    """)
    long countCreatedBy(@Param("travelerId") UUID travelerId, @Param("since") LocalDateTime since);

    /**
     * All threads where the user is participant — either traveler directly,
     * or sender via the linked package_request.
     * Used by GET /negotiations/me to power the inbox view.
     */
    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.travelerId = :userId
           OR t.packageRequestId IN (
                SELECT p.id FROM PackageRequestEntity p WHERE p.senderId = :userId
           )
        ORDER BY t.lastActivityAt DESC
    """)
    List<NegotiationThreadEntity> findByParticipant(@Param("userId") UUID userId);
}
