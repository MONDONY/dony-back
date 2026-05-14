package com.dony.api.requests.repository;

import com.dony.api.requests.entity.NegotiationMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NegotiationMessageRepository extends JpaRepository<NegotiationMessageEntity, UUID> {
    List<NegotiationMessageEntity> findByThreadIdOrderByCreatedAtAsc(UUID threadId);
}
