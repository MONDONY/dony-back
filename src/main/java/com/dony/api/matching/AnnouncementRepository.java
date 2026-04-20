package com.dony.api.matching;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<AnnouncementEntity, UUID> {
    
    Page<AnnouncementEntity> findByTravelerId(UUID travelerId, Pageable pageable);
}
