package com.dony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface BidRepository extends JpaRepository<BidEntity, UUID> {

    long countByAnnouncementId(UUID announcementId);

    boolean existsByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    boolean existsBySenderIdAndAnnouncementIdAndStatusIn(UUID senderId, UUID announcementId, List<BidStatus> statuses);

    List<BidEntity> findByAnnouncementId(UUID announcementId);

    List<BidEntity> findByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    List<BidEntity> findBySenderId(UUID senderId);

    // For H-2 alert scheduler: ACCEPTED bids with handover starting in ≤ 2h, not yet alerted, not confirmed
    @Query("SELECT b FROM BidEntity b WHERE b.status = 'ACCEPTED' " +
           "AND b.handoverWindowStart IS NOT NULL " +
           "AND b.handoverWindowStart <= :threshold " +
           "AND b.handoverWindowStart > :now " +
           "AND b.voyageurConfirmed = false " +
           "AND b.h2AlertSentAt IS NULL")
    List<BidEntity> findBidsNeedingH2Alert(@Param("now") LocalDateTime now,
                                            @Param("threshold") LocalDateTime threshold);
}
