package com.dony.api.addressbook.recipient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecipientRepository extends JpaRepository<RecipientEntity, UUID> {

    List<RecipientEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<RecipientEntity> findByUserIdAndId(UUID userId, UUID id);
}
