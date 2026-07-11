package com.dony.api.automation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AutomationCapacityWatermarkRepository
        extends JpaRepository<AutomationCapacityWatermarkEntity, UUID> {

    Optional<AutomationCapacityWatermarkEntity> findByAnnouncementId(UUID announcementId);
}
