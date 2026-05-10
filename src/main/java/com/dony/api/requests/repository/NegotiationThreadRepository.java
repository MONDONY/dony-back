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

    Optional<NegotiationThreadEntity> findByPackageRequestIdAndTravelerId(UUID packageRequestId, UUID travelerId);

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
}
