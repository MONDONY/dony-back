package com.dony.api.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    Optional<ConversationEntity> findByBidId(UUID bidId);

    Optional<ConversationEntity> findByFirestoreConversationId(String firestoreConversationId);

    @Query("SELECT c FROM ConversationEntity c WHERE c.senderId = :userId OR c.travelerId = :userId")
    Page<ConversationEntity> findByParticipant(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c WHERE (c.senderId = :userId OR c.travelerId = :userId) AND c.id = :id")
    Optional<ConversationEntity> findByIdAndParticipant(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM ConversationEntity c " +
           "WHERE c.bidId = :bidId AND (c.senderId = :userId OR c.travelerId = :userId)")
    Optional<ConversationEntity> findByBidIdAndParticipant(
            @Param("bidId") UUID bidId, @Param("userId") UUID userId);

    /**
     * Native query bypassing the @Where filter — checks if a (soft-)deleted
     * conversation ever existed for this bid. Used to block re-creation.
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM conversations " +
                   "WHERE bid_id = :bidId AND deleted_at IS NOT NULL)",
           nativeQuery = true)
    boolean existsDeletedConversationByBidId(@Param("bidId") UUID bidId);
}
