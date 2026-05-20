package com.dony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AnnouncementPriceGridItemRepository extends JpaRepository<AnnouncementPriceGridItemEntity, UUID> {
    List<AnnouncementPriceGridItemEntity> findByAnnouncementIdOrderByPositionAsc(UUID announcementId);
}
