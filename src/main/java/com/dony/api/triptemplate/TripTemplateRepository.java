package com.dony.api.triptemplate;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripTemplateRepository extends JpaRepository<TripTemplateEntity, UUID> {

    List<TripTemplateEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<TripTemplateEntity> findByUserIdAndId(UUID userId, UUID id);
}
