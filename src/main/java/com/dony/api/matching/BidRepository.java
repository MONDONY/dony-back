package com.dony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface BidRepository extends JpaRepository<BidEntity, UUID> {
    
    long countByAnnouncementId(UUID announcementId);
    
    boolean existsByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);
}
