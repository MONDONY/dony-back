package com.dony.api.cancellation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RematchSuggestionRepository extends JpaRepository<RematchSuggestionEntity, UUID> {
    List<RematchSuggestionEntity> findByCancellationId(UUID cancellationId);
    List<RematchSuggestionEntity> findByAnnouncementId(UUID announcementId);
}
