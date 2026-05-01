package com.dony.api.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    // Raw lookup by bid — no visibility filter (used internally for creation checks)
    Optional<ConversationEntity> findByBidId(UUID bidId);

    Optional<ConversationEntity> findByFirestoreConversationId(String firestoreConversationId);

    // Only return conversations where the requesting user has NOT deleted their copy
    @Query("SELECT c FROM ConversationEntity c WHERE " +
           "(c.senderId = :userId AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL)")
    Page<ConversationEntity> findByParticipant(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND (" +
           "(c.senderId = :userId AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL))")
    Optional<ConversationEntity> findByIdAndParticipant(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM ConversationEntity c WHERE c.bidId = :bidId AND (" +
           "(c.senderId = :userId AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL))")
    Optional<ConversationEntity> findByBidIdAndParticipant(
            @Param("bidId") UUID bidId, @Param("userId") UUID userId);

    // Ignore-deleted variants — used for restore flows (bypass per-user visibility filter)
    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND (c.senderId = :userId OR c.travelerId = :userId)")
    Optional<ConversationEntity> findByIdAndParticipantIgnoreDeleted(
            @Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM ConversationEntity c WHERE c.bidId = :bidId AND (c.senderId = :userId OR c.travelerId = :userId)")
    Optional<ConversationEntity> findByBidIdAndParticipantIgnoreDeleted(
            @Param("bidId") UUID bidId, @Param("userId") UUID userId);
}
