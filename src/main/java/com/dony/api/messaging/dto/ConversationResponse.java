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
        String tripDate,       // ISO LocalDate string, e.g. "2026-01-12"
        Double tripWeightKg,   // kg from bid (package weight)
        String bidStatus       // BID_ACCEPTED | DELIVERY_CONFIRMED | TRIP_CANCELLED | null
) {}
