package com.dony.api.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AdminAlertRepository extends JpaRepository<AdminAlertEntity, UUID> {

    List<AdminAlertEntity> findByTypeAndResolved(String type, boolean resolved);

    @Query("""
            SELECT a FROM AdminAlertEntity a
            WHERE (:type IS NULL OR a.type = :type)
              AND (:severity IS NULL OR a.severity = :severity)
              AND (:resolved IS NULL OR a.resolved = :resolved)
            """)
    Page<AdminAlertEntity> findFiltered(
            @Param("type") String type,
            @Param("severity") String severity,
            @Param("resolved") Boolean resolved,
            Pageable pageable
    );
}
