package com.dony.api.admin.dto;

/**
 * Message Firestore vu du back-office. `createdAt` = sentAt ISO-8601 tel
 * que stocké côté Firestore.
 */
public record AdminMessageResponse(
        String id,
        String conversationId,
        String senderName,
        String content,
        boolean flagged,
        boolean deleted,
        String createdAt
) {
}
