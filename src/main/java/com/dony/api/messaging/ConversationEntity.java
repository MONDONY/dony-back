package com.dony.api.messaging;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "conversations")
@Where(clause = "deleted_at IS NULL")
public class ConversationEntity extends BaseEntity {

    @Column(name = "bid_id", nullable = false, unique = true)
    private UUID bidId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "firestore_conversation_id", nullable = false, unique = true)
    private String firestoreConversationId;

    public ConversationEntity() {}

    public ConversationEntity(UUID bidId, UUID senderId, UUID travelerId, String firestoreConversationId) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
        this.firestoreConversationId = firestoreConversationId;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public UUID getTravelerId() { return travelerId; }
    public String getFirestoreConversationId() { return firestoreConversationId; }
}
