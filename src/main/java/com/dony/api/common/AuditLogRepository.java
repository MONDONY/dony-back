package com.dony.api.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE (:action IS NULL OR a.action = :action)
          AND (:entityType IS NULL OR a.entityType = :entityType)
          AND (:actorId IS NULL OR a.actorId = :actorId)
          AND (CAST(:from AS timestamp) IS NULL OR a.createdAt >= :from)
          AND (CAST(:to AS timestamp) IS NULL OR a.createdAt <= :to)
        ORDER BY a.createdAt DESC
        """)
    Page<AuditLogEntity> findFiltered(
        @Param("action") String action,
        @Param("entityType") String entityType,
        @Param("actorId") UUID actorId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );
}
