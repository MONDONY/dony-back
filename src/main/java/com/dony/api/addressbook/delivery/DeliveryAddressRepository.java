package com.dony.api.addressbook.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryAddressRepository extends JpaRepository<DeliveryAddressEntity, UUID> {

    List<DeliveryAddressEntity> findByUserIdOrderByIsDefaultDescUpdatedAtDesc(UUID userId);

    Optional<DeliveryAddressEntity> findByUserIdAndId(UUID userId, UUID id);

    @Query("SELECT e FROM DeliveryAddressEntity e WHERE e.userId = :userId AND e.isDefault = true")
    Optional<DeliveryAddressEntity> findDefaultByUserId(UUID userId);
}
