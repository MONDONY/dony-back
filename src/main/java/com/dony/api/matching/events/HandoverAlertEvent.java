package com.dony.api.matching.events;

import java.time.LocalDateTime;
import java.util.UUID;

public record HandoverAlertEvent(
        UUID bidId,
        UUID senderId,
        String handoverLocation,
        LocalDateTime handoverWindowStart,
        LocalDateTime handoverWindowEnd
) {}
