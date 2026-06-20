package com.dony.api.alerts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CorridorAlertRepository extends JpaRepository<CorridorAlertEntity, UUID> {

    List<CorridorAlertEntity> findAllByTravelerId(UUID travelerId);

    long countByTravelerId(UUID travelerId);

    List<CorridorAlertEntity> findAllByActiveTrue();
}
