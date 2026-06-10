package com.dony.api.matching;

import com.dony.api.payments.cash.CommissionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidRepository extends JpaRepository<BidEntity, UUID> {

    long countByAnnouncementId(UUID announcementId);

    long countByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    @Query("""
        SELECT COUNT(b) FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
    """)
    long countByAnnouncementTravelerIdAndStatus(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status);

    @Query("""
        SELECT COUNT(b) FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.createdAt BETWEEN :from AND :to
    """)
    long countDeliveredBidsForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    @Query("""
        SELECT COALESCE(SUM(b.weightKg), 0)
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.createdAt BETWEEN :from AND :to
    """)
    java.math.BigDecimal sumDeliveredKgForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /**
     * Counts only bids that are currently visible to the traveler on their announcement
     * (PENDING demands awaiting traveler action). Excludes AWAITING_PAYMENT (sender hasn't paid),
     * CANCELLED, REJECTED, COMPLETED — none of which appear in the traveler's pending list.
     */
    @Query("SELECT COUNT(b) FROM BidEntity b WHERE b.announcementId = :announcementId " +
           "AND b.status IN (com.dony.api.matching.BidStatus.PENDING, " +
           "                 com.dony.api.matching.BidStatus.PAYMENT_ESCROWED) " +
           "AND b.deletedByTraveler = false")
    long countVisibleByAnnouncementId(@Param("announcementId") UUID announcementId);

    List<BidEntity> findByAnnouncementIdAndStatusIn(UUID announcementId, List<BidStatus> statuses);

    long countByAnnouncementIdAndStatusIn(UUID announcementId, List<BidStatus> statuses);

    boolean existsByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    boolean existsByAnnouncementIdAndStatusIn(UUID announcementId, List<BidStatus> statuses);

    boolean existsBySenderIdAndAnnouncementIdAndStatusIn(UUID senderId, UUID announcementId, List<BidStatus> statuses);

    Optional<BidEntity> findBySenderIdAndAnnouncementIdAndStatus(UUID senderId, UUID announcementId, BidStatus status);

    List<BidEntity> findByAnnouncementId(UUID announcementId);

    List<BidEntity> findByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    List<BidEntity> findBySenderId(UUID senderId);

    // For H-2 alert scheduler: ACCEPTED bids with handover starting in ≤ 2h, not yet alerted, not confirmed
    Optional<BidEntity> findByTrackingNumber(String trackingNumber);

    Optional<BidEntity> findByTrackingToken(String trackingToken);

    Optional<BidEntity> findByPaymentIntentId(String paymentIntentId);

    Optional<BidEntity> findByLinkedNegotiationThreadId(UUID linkedNegotiationThreadId);

    List<BidEntity> findByStatusAndAwaitingPaymentExpiresAtBefore(
            BidStatus status, LocalDateTime threshold);

    @Query("SELECT b FROM BidEntity b WHERE b.status = 'ACCEPTED' " +
           "AND b.handoverWindowStart IS NOT NULL " +
           "AND b.handoverWindowStart <= :threshold " +
           "AND b.handoverWindowStart > :now " +
           "AND b.voyageurConfirmed = false " +
           "AND b.h2AlertSentAt IS NULL")
    List<BidEntity> findBidsNeedingH2Alert(@Param("now") LocalDateTime now,
                                            @Param("threshold") LocalDateTime threshold);

    // No-show detection: ACCEPTED bids with handoverWindowEnd > 1h ago, no DEPART scan, not yet marked NO_SHOW
    @Query("SELECT b FROM BidEntity b WHERE b.status = 'ACCEPTED' " +
           "AND b.handoverWindowEnd IS NOT NULL " +
           "AND b.handoverWindowEnd < :cutoff " +
           "AND b.noShowAt IS NULL " +
           "AND b.deletedAt IS NULL " +
           "AND NOT EXISTS (SELECT t FROM TrackingEventEntity t WHERE t.bidId = b.id AND t.eventType = 'DEPART')")
    List<BidEntity> findNoShowBids(@Param("cutoff") LocalDateTime cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BidEntity b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<BidEntity> findByIdForUpdate(@Param("id") UUID id);

    // Completed deliveries for a given traveler (via announcement ownership)
    @Query("SELECT b FROM BidEntity b JOIN AnnouncementEntity a ON b.announcementId = a.id " +
           "WHERE a.travelerId = :travelerId AND b.status = 'COMPLETED'")
    List<BidEntity> findCompletedBidsByTravelerId(@Param("travelerId") UUID travelerId);

    @Query("""
        SELECT b FROM BidEntity b JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId
          AND (:status IS NULL OR b.status = :status)
          AND (:announcementId IS NULL OR b.announcementId = :announcementId)
          AND (:q IS NULL OR UPPER(b.trackingNumber) LIKE UPPER(CONCAT('%', CAST(:q AS string), '%')))
        ORDER BY b.createdAt DESC
        """)
    Page<BidEntity> findByTravelerIdFiltered(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("announcementId") UUID announcementId,
            @Param("q") String q,
            Pageable pageable);

    @Query("""
        SELECT b FROM BidEntity b, AnnouncementEntity a
        WHERE b.announcementId = a.id
          AND b.status = com.dony.api.matching.BidStatus.PENDING
          AND b.createdAt < :minGraceThreshold
          AND (
                b.createdAt < :twentyFourHoursAgo
             OR a.departureDate <= :halfDayThresholdDate
          )
        """)
    List<BidEntity> findPendingTimedOut(
            @Param("twentyFourHoursAgo") LocalDateTime twentyFourHoursAgo,
            @Param("halfDayThresholdDate") java.time.LocalDate halfDayThresholdDate,
            @Param("minGraceThreshold") LocalDateTime minGraceThreshold
    );

    @Modifying
    @Query("UPDATE BidEntity b SET b.status = com.dony.api.matching.BidStatus.CANCELLED " +
           "WHERE b.senderId = :userId " +
           "AND b.status IN (com.dony.api.matching.BidStatus.PENDING, " +
           "                 com.dony.api.matching.BidStatus.PAYMENT_ESCROWED, " +
           "                 com.dony.api.matching.BidStatus.ACCEPTED, " +
           "                 com.dony.api.matching.BidStatus.AWAITING_PAYMENT)")
    int cancelOpenSenderBidsByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE BidEntity b SET b.status = com.dony.api.matching.BidStatus.CANCELLED " +
           "WHERE b.announcementId IN " +
           "  (SELECT a.id FROM AnnouncementEntity a WHERE a.travelerId = :userId) " +
           "AND b.status IN (com.dony.api.matching.BidStatus.PENDING, " +
           "                 com.dony.api.matching.BidStatus.PAYMENT_ESCROWED, " +
           "                 com.dony.api.matching.BidStatus.ACCEPTED, " +
           "                 com.dony.api.matching.BidStatus.AWAITING_PAYMENT)")
    int cancelOpenTravelerBidsByUserId(@Param("userId") UUID userId);

    // Retourne le bid COMPLETED le plus récent pour lequel userId n'a pas encore noté
    // (en tant qu'expéditeur OU voyageur via la jointure avec announcements)
    @Query(nativeQuery = true, value = """
        SELECT b.* FROM bids b
        JOIN announcements a ON b.announcement_id = a.id
        WHERE b.status = 'COMPLETED'
          AND b.deleted_at IS NULL
          AND (b.sender_id = :userId OR a.traveler_id = :userId)
          AND NOT EXISTS (
              SELECT 1 FROM ratings r
              WHERE r.bid_id = b.id
                AND r.rater_id = :userId
                AND r.deleted_at IS NULL
          )
        ORDER BY b.updated_at DESC
        LIMIT 1
        """)
    Optional<BidEntity> findPendingRatingForUser(@Param("userId") UUID userId);

    /**
     * Counts completed deliveries for a given sender.
     * Used by {@code DeliveryConfirmedReferralListener} to detect the first delivery.
     * Explicit deleted_at filter because custom @Query bypasses @Where in some Hibernate 6 versions.
     */
    @Query("SELECT COUNT(b) FROM BidEntity b WHERE b.senderId = :senderId AND b.status = :status AND b.deletedAt IS NULL")
    long countByStatusAndSenderId(@Param("status") BidStatus status, @Param("senderId") UUID senderId);

    /**
     * Returns past completed bookings for a sender, with traveler info and how many trips
     * that sender has done with each traveler. Used by the rebooking feature.
     */
    @Query(value = """
        SELECT
            b.id                                               AS bid_id,
            a.traveler_id                                      AS traveler_id,
            u.first_name || ' ' || u.last_name                AS traveler_name,
            CASE WHEN u.is_pro_account THEN 'PRO' ELSE NULL END AS traveler_badge,
            a.departure_city                                   AS departure_city,
            a.arrival_city                                     AS arrival_city,
            a.departure_date                                   AS last_trip_date,
            COUNT(b2.id) OVER (PARTITION BY a2.traveler_id)    AS completed_trips
        FROM bids b
        JOIN announcements a  ON b.announcement_id = a.id AND a.deleted_at IS NULL
        JOIN users u          ON a.traveler_id = u.id
        JOIN bids b2          ON b2.sender_id = b.sender_id
                             AND b2.status = 'COMPLETED'
                             AND b2.deleted_at IS NULL
        JOIN announcements a2 ON b2.announcement_id = a2.id
                             AND a2.traveler_id = a.traveler_id
                             AND a2.deleted_at IS NULL
        WHERE b.sender_id = :senderId
          AND b.status = 'COMPLETED'
          AND b.deleted_at IS NULL
        ORDER BY a.departure_date DESC
        """, nativeQuery = true)
    List<Object[]> findPastBookingsBySender(@Param("senderId") UUID senderId);

    List<BidEntity> findByCommissionStatusAndUpdatedAtBefore(
            CommissionStatus commissionStatus, LocalDateTime before);

    /**
     * True if there is at least one active (in-flight) transaction between two users,
     * in either direction (sender↔traveler). Used to prevent blocking a user mid-deal.
     */
    @Query("""
        SELECT COUNT(b) > 0 FROM BidEntity b
        JOIN AnnouncementEntity a ON a.id = b.announcementId
        WHERE b.status IN :activeStatuses AND (
              (b.senderId = :userA AND a.travelerId = :userB)
           OR (b.senderId = :userB AND a.travelerId = :userA))
        """)
    boolean hasActiveTransactionBetween(
            @Param("userA") UUID userA,
            @Param("userB") UUID userB,
            @Param("activeStatuses") List<BidStatus> activeStatuses);
}
