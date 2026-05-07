package com.dony.api.matching;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<AnnouncementEntity, UUID>,
        JpaSpecificationExecutor<AnnouncementEntity> {

    Page<AnnouncementEntity> findByTravelerId(UUID travelerId, Pageable pageable);

    /**
     * Finds ACTIVE or FULL announcements whose departure time has been reached,
     * based on today's date and current time in the announcement's timezone (pre-resolved by caller).
     * Announcements without departure_time are treated as departing at 00:00.
     */
    @Query("""
        SELECT a FROM AnnouncementEntity a
        WHERE a.status IN ('ACTIVE', 'FULL')
        AND (
            a.departureDate < :today
            OR (a.departureDate = :today AND (a.departureTime IS NULL OR a.departureTime <= :nowTime))
        )
    """)
    List<AnnouncementEntity> findDepartedActiveAnnouncements(
        @Param("today") LocalDate today,
        @Param("nowTime") LocalTime nowTime
    );

    /**
     * Returns IDs of announcements whose pickup coordinates fall within {@code radiusKm}
     * of (lat, lng). Excludes rows with NULL pickup coordinates.
     * Uses Haversine formula (Earth radius = 6371 km).
     *
     * <p>Returns UUIDs as {@code String} to remain compatible with both PostgreSQL
     * (which returns UUID objects) and H2 in test mode (which may return byte arrays).
     * Callers must convert via {@code UUID.fromString(...)}.
     */
    @Query(value = """
        SELECT CAST(id AS VARCHAR) FROM announcements
        WHERE deleted_at IS NULL
          AND status = 'ACTIVE'
          AND pickup_lat IS NOT NULL
          AND pickup_lng IS NOT NULL
          AND (
            6371 * acos(
              cos(radians(:lat)) * cos(radians(pickup_lat))
              * cos(radians(pickup_lng) - radians(:lng))
              + sin(radians(:lat)) * sin(radians(pickup_lat))
            )
          ) <= :radiusKm
        """, nativeQuery = true)
    List<String> findIdsWithinPickupRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusKm") double radiusKm
    );

    @Modifying
    @Query("UPDATE AnnouncementEntity a SET a.status = com.dony.api.matching.AnnouncementStatus.CANCELLED " +
           "WHERE a.travelerId = :userId AND a.status IN " +
           "(com.dony.api.matching.AnnouncementStatus.ACTIVE, com.dony.api.matching.AnnouncementStatus.FULL)")
    int cancelOpenAnnouncementsByUserId(@Param("userId") UUID userId);
}
