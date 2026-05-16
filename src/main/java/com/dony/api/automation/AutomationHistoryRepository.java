package com.dony.api.automation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AutomationHistoryRepository extends JpaRepository<AutomationHistoryEntity, UUID> {

    List<AutomationHistoryEntity> findByTravelerIdOrderByTriggeredAtDesc(UUID travelerId, Pageable pageable);

    long countByTravelerIdAndTriggeredAtAfter(UUID travelerId, LocalDateTime since);
}
