package com.dony.api.favorites;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavoriteRepository extends JpaRepository<FavoriteEntity, UUID> {

    boolean existsByUserIdAndTargetTypeAndTargetId(UUID userId, FavoriteTargetType targetType, UUID targetId);

    Optional<FavoriteEntity> findByUserIdAndTargetTypeAndTargetId(UUID userId, FavoriteTargetType targetType, UUID targetId);

    List<FavoriteEntity> findByUserIdAndTargetTypeOrderByCreatedAtDesc(UUID userId, FavoriteTargetType targetType);

    List<FavoriteEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Inclut les lignes soft-deleted pour pouvoir les réactiver (toggle on après off). */
    @Query(value = "SELECT * FROM favorites WHERE user_id = :userId AND target_type = :type AND target_id = :targetId LIMIT 1", nativeQuery = true)
    Optional<FavoriteEntity> findIncludingDeleted(@Param("userId") UUID userId, @Param("type") String type, @Param("targetId") UUID targetId);

    @Query("SELECT f.targetId FROM FavoriteEntity f WHERE f.userId = :userId AND f.targetType = :type")
    List<UUID> findTargetIds(@Param("userId") UUID userId, @Param("type") FavoriteTargetType type);

    /**
     * Soft-delete des favoris TRIP dont le trajet a atteint un état terminal
     * (COMPLETED/CANCELLED) — évite l'accumulation indéfinie de favoris sur des
     * trajets qui ne sont plus jamais actionnables. Idempotent : ne retouche pas
     * les lignes déjà soft-deleted.
     *
     * @return le nombre de lignes nettoyées
     */
    @Modifying
    @Query(value = """
            UPDATE favorites SET deleted_at = now()
            WHERE target_type = 'TRIP' AND deleted_at IS NULL
              AND target_id IN (
                  SELECT id FROM announcements WHERE status IN ('COMPLETED', 'CANCELLED')
              )
            """, nativeQuery = true)
    int softDeleteTripFavoritesForTerminalAnnouncements();

    /**
     * Même nettoyage pour les favoris PACKAGE_REQUEST dont la demande a atteint
     * un état terminal (COMPLETED/CANCELLED/EXPIRED).
     *
     * @return le nombre de lignes nettoyées
     */
    @Modifying
    @Query(value = """
            UPDATE favorites SET deleted_at = now()
            WHERE target_type = 'PACKAGE_REQUEST' AND deleted_at IS NULL
              AND target_id IN (
                  SELECT id FROM package_requests WHERE status IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
              )
            """, nativeQuery = true)
    int softDeletePackageRequestFavoritesForTerminalRequests();
}
