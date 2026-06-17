package com.dony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BidPhotoRepository extends JpaRepository<BidPhotoEntity, UUID> {

    List<BidPhotoEntity> findByBidIdAndStatusOrderByPositionAsc(UUID bidId, BidPhotoStatus status);

    List<BidPhotoEntity> findByStatus(BidPhotoStatus status);
}
