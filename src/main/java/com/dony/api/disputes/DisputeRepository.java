package com.dony.api.disputes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<DisputeEntity, UUID> {
    Optional<DisputeEntity> findByBidId(UUID bidId);

    List<DisputeEntity> findByTravelerIdOrderByCreatedAtDesc(UUID travelerId);
}
