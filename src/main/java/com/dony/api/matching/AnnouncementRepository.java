package com.dony.api.matching;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<AnnouncementEntity, UUID>,
        JpaSpecificationExecutor<AnnouncementEntity> {

    Page<AnnouncementEntity> findByTravelerId(UUID travelerId, Pageable pageable);

    Page<AnnouncementEntity> findByTravelerIdAndStatus(UUID travelerId, AnnouncementStatus status, Pageable pageable);

    @Query("""
            SELECT a FROM AnnouncementEntity a
            WHERE a.travelerId = :travelerId
              AND (:status    IS NULL OR a.status = :status)
              AND (:q         IS NULL
                   OR UPPER(a.departureCity) LIKE UPPER(CONCAT('%', CAST(:q AS string), '%'))
                   OR UPPER(a.arrivalCity)   LIKE UPPER(CONCAT('%', CAST(:q AS string), '%')))
              AND (CAST(:date AS date) IS NULL OR a.departureDate = :date)
              AND (CAST(:dateFrom AS date) IS NULL OR a.departureDate >= :dateFrom)
              AND (CAST(:dateTo AS date) IS NULL OR a.departureDate <= :dateTo)
              AND (:departure IS NULL OR LOWER(a.departureCity) = LOWER(CAST(:departure AS string)))
              AND (:arrival   IS NULL OR LOWER(a.arrivalCity)   = LOWER(CAST(:arrival AS string)))
            ORDER BY a.createdAt DESC
            """)
    Page<AnnouncementEntity> findByTravelerIdFiltered(
            @Param("travelerId") UUID travelerId,
            @Param("status")     AnnouncementStatus status,
            @Param("q")          String q,
            @Param("date")       java.time.LocalDate date,
            @Param("dateFrom")   java.time.LocalDate dateFrom,
            @Param("dateTo")     java.time.LocalDate dateTo,
            @Param("departure")  String departure,
            @Param("arrival")    String arrival,
            Pageable pageable);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AnnouncementEntity a WHERE a.id = :id AND a.deletedAt IS NULL")
    Optional<AnnouncementEntity> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE AnnouncementEntity a SET a.status = com.dony.api.matching.AnnouncementStatus.CANCELLED " +
           "WHERE a.travelerId = :userId AND a.status IN " +
           "(com.dony.api.matching.AnnouncementStatus.ACTIVE, com.dony.api.matching.AnnouncementStatus.FULL)")
    int cancelOpenAnnouncementsByUserId(@Param("userId") UUID userId);

    long countByTravelerIdAndCreatedAtBetween(UUID travelerId, LocalDateTime from, LocalDateTime to);

    long countByTravelerIdAndStatusAndCreatedAtBetween(
            UUID travelerId, AnnouncementStatus status, LocalDateTime from, LocalDateTime to);

    List<AnnouncementEntity> findByTravelerIdAndStatusAndCreatedAtBetween(
            UUID travelerId, AnnouncementStatus status, LocalDateTime from, LocalDateTime to);

    long countByTravelerIdAndStatus(UUID travelerId, AnnouncementStatus status);

    long countByTravelerIdAndStatusIn(
            UUID travelerId, java.util.Collection<AnnouncementStatus> statuses);

    @Query("""
        SELECT new com.dony.api.matching.dto.TravelerStatsDto$DestinationStat(
            a.departureCity, a.arrivalCity, COUNT(a))
        FROM AnnouncementEntity a
        WHERE a.travelerId = :travelerId
        GROUP BY a.departureCity, a.arrivalCity
        ORDER BY COUNT(a) DESC
    """)
    List<com.dony.api.matching.dto.TravelerStatsDto.DestinationStat> findTopDestinationsForTraveler(
            @Param("travelerId") UUID travelerId, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query("UPDATE AnnouncementEntity a SET a.travelerIsPro = :isPro " +
           "WHERE a.travelerId = :travelerId AND a.status IN " +
           "(com.dony.api.matching.AnnouncementStatus.ACTIVE, com.dony.api.matching.AnnouncementStatus.FULL)")
    int updateTravelerProStatus(@Param("travelerId") UUID travelerId, @Param("isPro") boolean isPro);

    @Query("SELECT a FROM AnnouncementEntity a WHERE a.travelerId = :travelerId " +
           "AND a.status IN (com.dony.api.matching.AnnouncementStatus.ACTIVE, com.dony.api.matching.AnnouncementStatus.FULL)")
    List<AnnouncementEntity> findActiveByTravelerId(@Param("travelerId") UUID travelerId);

    /**
     * Returns the next upcoming announcement for a given traveler on a specific corridor
     * (departureCity → arrivalCity) with a departure date >= fromDate.
     * Only ACTIVE or FULL announcements are considered (not COMPLETED, CANCELLED, ARCHIVED).
     * Used by RebookingService for 1-tap rebooking.
     */
    @Query("""
        SELECT a FROM AnnouncementEntity a
        WHERE a.travelerId = :travelerId
          AND a.departureCity = :departureCity
          AND a.arrivalCity   = :arrivalCity
          AND a.departureDate >= :fromDate
          AND a.status IN (
              com.dony.api.matching.AnnouncementStatus.ACTIVE,
              com.dony.api.matching.AnnouncementStatus.FULL)
        ORDER BY a.departureDate ASC
        """)
    Optional<AnnouncementEntity> findNextUpcomingByTravelerAndCities(
        @Param("travelerId")    UUID travelerId,
        @Param("departureCity") String departureCity,
        @Param("arrivalCity")   String arrivalCity,
        @Param("fromDate")      LocalDate fromDate
    );

    /**
     * Returns recent announcements on a corridor (departure→arrival) with a future or
     * today departure date, ordered newest first. Used by PriceEstimationService.
     */
    @Query("""
        SELECT a FROM AnnouncementEntity a
        WHERE LOWER(a.departureCity) = LOWER(:departure)
          AND LOWER(a.arrivalCity)   = LOWER(:arrival)
          AND a.departureDate >= CURRENT_DATE
        ORDER BY a.createdAt DESC
    """)
    List<AnnouncementEntity> findRecentByCorridor(
        @Param("departure") String departure,
        @Param("arrival") String arrival,
        org.springframework.data.domain.Pageable pageable);
}
