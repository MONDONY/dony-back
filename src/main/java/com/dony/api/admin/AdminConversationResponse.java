package com.dony.api.admin;

import java.util.UUID;

public record AdminConversationResponse(
        String id,
        UUID bidId,
        String participantA,
        String participantB,
        String lastMessageAt,
        int messageCount,
        boolean flagged,
        String createdAt
) {}
