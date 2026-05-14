package com.dony.api.cancellation.events;

import com.dony.api.cancellation.CancellationReason;
import java.util.UUID;

public record CancellationConfirmedEvent(
        UUID bidId,
        UUID cancellationId,
        CancellationReason reason
) {}
