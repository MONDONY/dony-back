package com.dony.api.auth.events;

import com.dony.api.auth.FinalizationReason;
import java.util.UUID;

public class UserFinalizedEvent {

    private final UUID userId;
    private final FinalizationReason reason;

    public UserFinalizedEvent(UUID userId, FinalizationReason reason) {
        this.userId = userId;
        this.reason = reason;
    }

    public UUID getUserId() { return userId; }
    public FinalizationReason getReason() { return reason; }
}
