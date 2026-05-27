package com.dony.api.subscriptions.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SubscriberResponse(
        UUID senderId,
        String displayName,
        LocalDateTime subscribedAt
) {}
