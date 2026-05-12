package com.dony.api.addressbook.pickup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PickupAddressRepository extends JpaRepository<PickupAddressEntity, UUID> {

    List<PickupAddressEntity> findByUserIdOrderByIsDefaultDescUpdatedAtDesc(UUID userId);

    Optional<PickupAddressEntity> findByUserIdAndId(UUID userId, UUID id);

    @Query("SELECT p FROM PickupAddressEntity p WHERE p.userId = :userId AND p.isDefault = true")
    Optional<PickupAddressEntity> findDefaultByUserId(@Param("userId") UUID userId);
}
