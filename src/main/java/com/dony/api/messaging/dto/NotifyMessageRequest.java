package com.dony.api.messaging.dto;

import jakarta.validation.constraints.NotBlank;

public record NotifyMessageRequest(
    @NotBlank String conversationId,
    @NotBlank String senderFirebaseUid,
    String messagePreview
) {}
