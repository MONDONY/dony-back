package com.dony.api.favorites;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavoriteRepository extends JpaRepository<FavoriteEntity, UUID> {

    boolean existsByUserIdAndTargetTypeAndTargetId(UUID userId, FavoriteTargetType targetType, UUID targetId);

    Optional<FavoriteEntity> findByUserIdAndTargetTypeAndTargetId(UUID userId, FavoriteTargetType targetType, UUID targetId);

    List<FavoriteEntity> findByUserIdAndTargetTypeOrderByCreatedAtDesc(UUID userId, FavoriteTargetType targetType);

    /** Inclut les lignes soft-deleted pour pouvoir les réactiver (toggle on après off). */
    @Query(value = "SELECT * FROM favorites WHERE user_id = :userId AND target_type = :type AND target_id = :targetId LIMIT 1", nativeQuery = true)
    Optional<FavoriteEntity> findIncludingDeleted(@Param("userId") UUID userId, @Param("type") String type, @Param("targetId") UUID targetId);

    @Query("SELECT f.targetId FROM FavoriteEntity f WHERE f.userId = :userId AND f.targetType = :type")
    List<UUID> findTargetIds(@Param("userId") UUID userId, @Param("type") FavoriteTargetType type);
}
