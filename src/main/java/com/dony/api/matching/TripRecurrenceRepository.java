package com.dony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRecurrenceRepository extends JpaRepository<TripRecurrenceEntity, UUID> {

    List<TripRecurrenceEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<TripRecurrenceEntity> findByUserIdAndId(UUID userId, UUID id);

    List<TripRecurrenceEntity> findByActiveTrue();
}
