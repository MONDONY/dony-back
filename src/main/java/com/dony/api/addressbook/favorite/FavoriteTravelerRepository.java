package com.dony.api.addressbook.favorite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavoriteTravelerRepository extends JpaRepository<FavoriteTravelerEntity, UUID> {

    List<FavoriteTravelerEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<FavoriteTravelerEntity> findByUserIdAndTravelerId(UUID userId, UUID travelerId);

    boolean existsByUserIdAndTravelerId(UUID userId, UUID travelerId);
}
