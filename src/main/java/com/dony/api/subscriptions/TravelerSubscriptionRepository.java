package com.dony.api.subscriptions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TravelerSubscriptionRepository extends JpaRepository<TravelerSubscriptionEntity, UUID> {

    boolean existsBySenderIdAndTravelerId(UUID senderId, UUID travelerId);

    Optional<TravelerSubscriptionEntity> findBySenderIdAndTravelerId(UUID senderId, UUID travelerId);

    @Query("SELECT ts.senderId FROM TravelerSubscriptionEntity ts WHERE ts.travelerId = :travelerId")
    List<UUID> findSenderIdsByTravelerId(@Param("travelerId") UUID travelerId);

    List<TravelerSubscriptionEntity> findAllBySenderId(UUID senderId);

    List<TravelerSubscriptionEntity> findAllByTravelerId(UUID travelerId);

    @Query(value = "SELECT * FROM traveler_subscriptions WHERE sender_id = :senderId AND traveler_id = :travelerId LIMIT 1", nativeQuery = true)
    Optional<TravelerSubscriptionEntity> findBySenderIdAndTravelerIdIncludingDeleted(@Param("senderId") UUID senderId,
                                                                                     @Param("travelerId") UUID travelerId);

    @Query(value = """
        SELECT ts.traveler_id                                   AS traveler_id,
               (u.first_name || ' ' || u.last_name)             AS traveler_name,
               u.is_pro_account                                 AS is_pro,
               u.average_rating                                 AS average_rating,
               (SELECT COUNT(*) FROM announcements a
                  WHERE a.traveler_id = ts.traveler_id
                    AND a.deleted_at IS NULL
                    AND a.status IN ('ACTIVE','FULL'))          AS ongoing_trips,
               ts.push_enabled                                  AS push_enabled,
               ts.has_new                                       AS has_new,
               (SELECT a.id FROM announcements a WHERE a.traveler_id = ts.traveler_id
                  AND a.deleted_at IS NULL AND a.status IN ('ACTIVE','FULL')
                  ORDER BY a.created_at DESC LIMIT 1)            AS last_ann_id,
               (SELECT a.departure_city FROM announcements a WHERE a.traveler_id = ts.traveler_id
                  AND a.deleted_at IS NULL AND a.status IN ('ACTIVE','FULL')
                  ORDER BY a.created_at DESC LIMIT 1)            AS last_dep,
               (SELECT a.arrival_city FROM announcements a WHERE a.traveler_id = ts.traveler_id
                  AND a.deleted_at IS NULL AND a.status IN ('ACTIVE','FULL')
                  ORDER BY a.created_at DESC LIMIT 1)            AS last_arr,
               (SELECT a.price_per_kg FROM announcements a WHERE a.traveler_id = ts.traveler_id
                  AND a.deleted_at IS NULL AND a.status IN ('ACTIVE','FULL')
                  ORDER BY a.created_at DESC LIMIT 1)            AS last_price,
               (SELECT a.created_at FROM announcements a WHERE a.traveler_id = ts.traveler_id
                  AND a.deleted_at IS NULL AND a.status IN ('ACTIVE','FULL')
                  ORDER BY a.created_at DESC LIMIT 1)            AS last_published
        FROM traveler_subscriptions ts
        JOIN users u ON u.id = ts.traveler_id AND u.deleted_at IS NULL
        WHERE ts.sender_id = :senderId
          AND ts.deleted_at IS NULL
        ORDER BY ts.has_new DESC, ts.created_at DESC
        """, nativeQuery = true)
    List<Object[]> findEnrichedBySenderId(@Param("senderId") UUID senderId);
}
