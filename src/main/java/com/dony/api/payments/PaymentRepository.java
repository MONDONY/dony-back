package com.dony.api.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByBidId(UUID bidId);

    Optional<PaymentEntity> findByNegotiationThreadId(UUID negotiationThreadId);

    Optional<PaymentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    List<PaymentEntity> findByStatus(PaymentStatus status);

    /** Story 6.5 — Find all payments in a given status whose escrow started before the given threshold. */
    List<PaymentEntity> findByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime threshold);

    /** Story 9.8 — GDPR: check active escrow payments for given bid IDs. */
    boolean existsByBidIdInAndStatus(List<UUID> bidIds, PaymentStatus status);

    /**
     * Atomic status transition ESCROW → RELEASED.
     * Returns 1 if the row was updated, 0 if it was already in a non-ESCROW state.
     * Using this instead of a read-then-write prevents double-capture race conditions.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = 'RELEASED', p.escrowReleasedAt = :releasedAt WHERE p.id = :id AND p.status = 'ESCROW'")
    int markReleasedIfEscrow(@Param("id") UUID id, @Param("releasedAt") LocalDateTime releasedAt);

    /**
     * Atomic capture-once CAS guard. Returns 1 if the row was updated (first capture),
     * 0 if already captured or not in ESCROW status.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.capturedAt = :now WHERE p.id = :id AND p.capturedAt IS NULL AND p.status = com.dony.api.payments.PaymentStatus.ESCROW")
    int markCapturedIfEscrow(@Param("id") UUID id, @Param("now") Instant now);

    /** Story 9.8 — RGPD: check if the user has any active escrow payments (as sender or traveler). */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM PaymentEntity p " +
           "WHERE p.bidId IN " +
           "  (SELECT b.id FROM com.dony.api.matching.BidEntity b WHERE b.senderId = :userId " +
           "   UNION " +
           "   SELECT b2.id FROM com.dony.api.matching.BidEntity b2 " +
           "   JOIN com.dony.api.matching.AnnouncementEntity a ON b2.announcementId = a.id " +
           "   WHERE a.travelerId = :userId) " +
           "AND p.status = com.dony.api.payments.PaymentStatus.ESCROW")
    boolean hasActiveEscrowForUser(@Param("userId") UUID userId);

    @Query("""
        SELECT COALESCE(SUM(p.amount - p.commissionAmount), 0)
        FROM PaymentEntity p
        JOIN com.dony.api.matching.BidEntity b ON p.bidId = b.id
        JOIN com.dony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId
          AND p.status = :status
          AND p.createdAt BETWEEN :from AND :to
    """)
    java.math.BigDecimal sumCapturedRevenueForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") PaymentStatus status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    @Query("""
        SELECT COALESCE(SUM(p.amount), 0)
        FROM PaymentEntity p
        JOIN com.dony.api.matching.BidEntity b ON p.bidId = b.id
        WHERE b.announcementId = :announcementId
          AND p.status = :status
    """)
    java.math.BigDecimal sumGrossRevenueForAnnouncement(
            @Param("announcementId") UUID announcementId,
            @Param("status") PaymentStatus status);

    @Query("""
        SELECT COALESCE(SUM(p.amount - p.commissionAmount), 0)
        FROM PaymentEntity p
        JOIN com.dony.api.matching.BidEntity b ON p.bidId = b.id
        JOIN com.dony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId
          AND p.status = :status
    """)
    java.math.BigDecimal sumTotalCapturedRevenueForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") PaymentStatus status);
}
