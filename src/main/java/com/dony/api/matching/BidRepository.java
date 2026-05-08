package com.dony.api.matching;

import jakarta.persistence.LockModeType;
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

    /**
     * Counts only bids that are currently visible to the traveler on their announcement
     * (PENDING demands awaiting traveler action). Excludes AWAITING_PAYMENT (sender hasn't paid),
     * CANCELLED, REJECTED, COMPLETED — none of which appear in the traveler's pending list.
     */
    @Query("SELECT COUNT(b) FROM BidEntity b WHERE b.announcementId = :announcementId " +
           "AND b.status = com.dony.api.matching.BidStatus.PENDING " +
           "AND b.deletedByTraveler = false")
    long countVisibleByAnnouncementId(@Param("announcementId") UUID announcementId);

    boolean existsByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    boolean existsBySenderIdAndAnnouncementIdAndStatusIn(UUID senderId, UUID announcementId, List<BidStatus> statuses);

    Optional<BidEntity> findBySenderIdAndAnnouncementIdAndStatus(UUID senderId, UUID announcementId, BidStatus status);

    List<BidEntity> findByAnnouncementId(UUID announcementId);

    List<BidEntity> findByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    List<BidEntity> findBySenderId(UUID senderId);

    // For H-2 alert scheduler: ACCEPTED bids with handover starting in ≤ 2h, not yet alerted, not confirmed
    Optional<BidEntity> findByTrackingNumber(String trackingNumber);

    Optional<BidEntity> findByTrackingToken(String trackingToken);

    Optional<BidEntity> findByPaymentIntentId(String paymentIntentId);

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

    List<BidEntity> findByAnnouncementIdAndStatusIn(UUID announcementId, List<BidStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BidEntity b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<BidEntity> findByIdForUpdate(@Param("id") UUID id);

    // Completed deliveries for a given traveler (via announcement ownership)
    @Query("SELECT b FROM BidEntity b JOIN AnnouncementEntity a ON b.announcementId = a.id " +
           "WHERE a.travelerId = :travelerId AND b.status = 'COMPLETED'")
    List<BidEntity> findCompletedBidsByTravelerId(@Param("travelerId") UUID travelerId);

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
           "                 com.dony.api.matching.BidStatus.ACCEPTED, " +
           "                 com.dony.api.matching.BidStatus.AWAITING_PAYMENT)")
    int cancelOpenSenderBidsByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE BidEntity b SET b.status = com.dony.api.matching.BidStatus.CANCELLED " +
           "WHERE b.announcementId IN " +
           "  (SELECT a.id FROM AnnouncementEntity a WHERE a.travelerId = :userId) " +
           "AND b.status IN (com.dony.api.matching.BidStatus.PENDING, " +
           "                 com.dony.api.matching.BidStatus.ACCEPTED, " +
           "                 com.dony.api.matching.BidStatus.AWAITING_PAYMENT)")
    int cancelOpenTravelerBidsByUserId(@Param("userId") UUID userId);
}
