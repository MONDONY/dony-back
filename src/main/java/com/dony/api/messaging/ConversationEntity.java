package com.dony.api.messaging;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class ConversationEntity extends BaseEntity {

    @Column(name = "bid_id", nullable = false, unique = true)
    private UUID bidId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "firestore_conversation_id", nullable = false, unique = true)
    private String firestoreConversationId;

    @Column(name = "sender_deleted_at")
    private LocalDateTime senderDeletedAt;

    @Column(name = "traveler_deleted_at")
    private LocalDateTime travelerDeletedAt;

    @Column(name = "sender_archived_at")
    private LocalDateTime senderArchivedAt;

    @Column(name = "traveler_archived_at")
    private LocalDateTime travelerArchivedAt;

    public ConversationEntity() {}

    public ConversationEntity(UUID bidId, UUID senderId, UUID travelerId, String firestoreConversationId) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
        this.firestoreConversationId = firestoreConversationId;
    }

    public void deleteForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderDeletedAt = LocalDateTime.now(ZoneOffset.UTC);
        } else if (userId.equals(travelerId)) {
            this.travelerDeletedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public void restoreForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderDeletedAt = null;
        } else if (userId.equals(travelerId)) {
            this.travelerDeletedAt = null;
        }
    }

    public boolean isDeletedByUser(UUID userId) {
        if (userId.equals(senderId)) return senderDeletedAt != null;
        if (userId.equals(travelerId)) return travelerDeletedAt != null;
        return false;
    }

    public void archiveForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderArchivedAt = LocalDateTime.now(ZoneOffset.UTC);
        } else if (userId.equals(travelerId)) {
            this.travelerArchivedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public void unarchiveForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderArchivedAt = null;
        } else if (userId.equals(travelerId)) {
            this.travelerArchivedAt = null;
        }
    }

    public boolean isArchivedByUser(UUID userId) {
        if (userId.equals(senderId)) return senderArchivedAt != null;
        if (userId.equals(travelerId)) return travelerArchivedAt != null;
        return false;
    }

    public boolean isReadOnlyFor(UUID userId) {
        // Read-only when the OTHER party deleted, but current user hasn't
        if (userId.equals(senderId)) return travelerDeletedAt != null && senderDeletedAt == null;
        if (userId.equals(travelerId)) return senderDeletedAt != null && travelerDeletedAt == null;
        return false;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public UUID getTravelerId() { return travelerId; }
    public String getFirestoreConversationId() { return firestoreConversationId; }
    public LocalDateTime getSenderDeletedAt() { return senderDeletedAt; }
    public LocalDateTime getTravelerDeletedAt() { return travelerDeletedAt; }
    public LocalDateTime getSenderArchivedAt() { return senderArchivedAt; }
    public LocalDateTime getTravelerArchivedAt() { return travelerArchivedAt; }
}
