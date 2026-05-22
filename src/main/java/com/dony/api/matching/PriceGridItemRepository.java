package com.dony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PriceGridItemRepository extends JpaRepository<PriceGridItemEntity, UUID> {
    List<PriceGridItemEntity> findByTravelerIdOrderByPositionAsc(UUID travelerId);
    long countByTravelerId(UUID travelerId);
}
