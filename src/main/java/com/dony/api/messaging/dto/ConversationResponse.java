package com.dony.api.messaging.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID bidId,
        String firestoreConversationId,
        ParticipantDTO otherParticipant,
        String lastMessagePreview,
        LocalDateTime lastMessageAt,
        boolean hasUnread,
        // Trip fields — null until bid+announcement are available
        String tripOrigin,
        String tripDestination,
        String tripDate,
        Double tripWeightKg,
        String bidStatus,
        // True when the other party deleted: current user sees history but cannot send
        boolean readOnly,
        // True when the current user deleted their own copy (restorable via /restore)
        boolean deletedBySelf
) {}
