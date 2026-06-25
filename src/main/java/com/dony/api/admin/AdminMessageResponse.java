package com.dony.api.admin;

public record AdminMessageResponse(
        String id,
        String conversationId,
        String senderName,
        String content,
        boolean flagged,
        boolean deleted,
        String createdAt
) {}
