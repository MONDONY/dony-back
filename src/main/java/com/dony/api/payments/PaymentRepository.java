package com.dony.api.payments;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dony.api.matching.dto.AnnouncementRevenueRow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByBidId(UUID bidId);

    Optional<PaymentEntity> findByNegotiationThreadId(UUID negotiationThreadId);

    Optional<PaymentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    Optional<PaymentEntity> findByStripeChargeId(String chargeId);

    List<PaymentEntity> findByStatus(PaymentStatus status);

    /** Story 6.5 — Find all payments in a given status whose escrow started before the given threshold. */
    List<PaymentEntity> findByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime threshold);

    List<PaymentEntity> findAllByCreatedAtBetweenOrderByCreatedAtAsc(LocalDateTime from, LocalDateTime to);

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

    /**
     * Atomic status transition ESCROW → REFUNDED.
     * Returns 1 if the row was updated, 0 if it was already in a non-ESCROW state.
     * Guards the admin manual refund against a double-refund race.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = 'REFUNDED' WHERE p.id = :id AND p.status = 'ESCROW'")
    int markRefundedIfEscrow(@Param("id") UUID id);

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

    // Un paiement du flux négociation / trajet dédié a bidId = NULL (keyé sur le
    // thread) : LEFT JOIN + attribution via t.travelerId, sinon l'INNER JOIN sur
    // le bid jetait ces revenus (KPI « Revenus » à 0 pour les deals carte négociés).
    @Query("""
        SELECT COALESCE(SUM(p.amount - p.commissionAmount), 0)
        FROM PaymentEntity p
        LEFT JOIN com.dony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.dony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.dony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        WHERE (a.travelerId = :travelerId OR t.travelerId = :travelerId)
          AND p.status = :status
          AND p.createdAt BETWEEN :from AND :to
    """)
    java.math.BigDecimal sumCapturedRevenueForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") PaymentStatus status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /**
     * Revenus agrégés par annonce sur une période, alimentés par les paiements du
     * statut donné (RELEASED pour les analytics). Même clause WHERE que
     * {@link #sumCapturedRevenueForTraveler} (voyageur + statut + {@code createdAt}
     * dans la période), simplement groupée par annonce : la somme des
     * {@code gross − commission} de toutes les lignes se réconcilie donc exactement
     * avec le KPI « Revenus nets ». La commission remontée est celle réellement
     * prélevée (somme des {@code commissionAmount}, overrides figés inclus).
     */
    // Annonce effective = celle du bid (deals directs) ou, pour un paiement de
    // thread (bidId NULL), l'annonce du voyageur liée au thread — COALESCE des
    // deux, sinon les revenus négociés disparaissaient de la ventilation par annonce.
    @Query("""
        SELECT new com.dony.api.matching.dto.AnnouncementRevenueRow(
            ann.id, ann.departureCity, ann.arrivalCity, ann.departureDate,
            COUNT(p), COALESCE(SUM(p.amount), 0), COALESCE(SUM(p.commissionAmount), 0))
        FROM PaymentEntity p
        LEFT JOIN com.dony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.dony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.dony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        LEFT JOIN com.dony.api.matching.AnnouncementEntity ta ON t.travelerAnnouncementId = ta.id
        JOIN com.dony.api.matching.AnnouncementEntity ann ON ann.id = COALESCE(a.id, ta.id)
        WHERE ann.travelerId = :travelerId
          AND p.status = :status
          AND p.createdAt BETWEEN :from AND :to
        GROUP BY ann.id, ann.departureCity, ann.arrivalCity, ann.departureDate
        ORDER BY ann.departureDate DESC
    """)
    List<AnnouncementRevenueRow> findReleasedRevenueByAnnouncement(
            @Param("travelerId") UUID travelerId,
            @Param("status") PaymentStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
        SELECT COALESCE(SUM(p.amount - p.commissionAmount), 0)
        FROM PaymentEntity p
        LEFT JOIN com.dony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.dony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.dony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        WHERE (a.travelerId = :travelerId OR t.travelerId = :travelerId)
          AND p.status = :status
    """)
    java.math.BigDecimal sumTotalCapturedRevenueForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") PaymentStatus status);

    @Query("""
        SELECT p FROM PaymentEntity p
        LEFT JOIN com.dony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.dony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.dony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        WHERE (a.travelerId = :travelerId OR t.travelerId = :travelerId)
          AND p.status = 'RELEASED'
          AND p.createdAt BETWEEN :from AND :to
        ORDER BY p.createdAt ASC
    """)
    List<PaymentEntity> findReleasedByTravelerAndYear(
            @Param("travelerId") UUID travelerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT p.* FROM payments p
            WHERE p.deleted_at IS NULL
              AND (CAST(:status AS VARCHAR) IS NULL OR p.status = :status)
              AND (CAST(:from AS TIMESTAMP) IS NULL OR p.created_at >= CAST(:from AS TIMESTAMP))
              AND (CAST(:to AS TIMESTAMP) IS NULL OR p.created_at <= CAST(:to AS TIMESTAMP))
            ORDER BY p.created_at DESC
            """,
           countQuery = """
            SELECT COUNT(*) FROM payments p
            WHERE p.deleted_at IS NULL
              AND (CAST(:status AS VARCHAR) IS NULL OR p.status = :status)
              AND (CAST(:from AS TIMESTAMP) IS NULL OR p.created_at >= CAST(:from AS TIMESTAMP))
              AND (CAST(:to AS TIMESTAMP) IS NULL OR p.created_at <= CAST(:to AS TIMESTAMP))
            """,
           nativeQuery = true)
    Page<PaymentEntity> findAdminFiltered(
            @Param("status") String status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            Pageable pageable);
}
