package com.yadony.api.admin.dto;

import com.yadony.api.messaging.ConversationEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Conversation vue du back-office. `id` = identifiant Firestore : c'est lui
 * que le front réutilise pour lister/supprimer les messages.
 */
public record AdminConversationResponse(
        String id,
        UUID bidId,
        String participantA,
        String participantB,
        String lastMessageAt,
        int messageCount,
        boolean flagged,
        LocalDateTime createdAt
) {
    public static AdminConversationResponse from(ConversationEntity e,
                                                 String senderName,
                                                 String travelerName,
                                                 String lastMessageAt) {
        return new AdminConversationResponse(
                e.getFirestoreConversationId(),
                e.getBidId(),
                senderName,
                travelerName,
                lastMessageAt,
                0,
                false,
                e.getCreatedAt()
        );
    }
}
