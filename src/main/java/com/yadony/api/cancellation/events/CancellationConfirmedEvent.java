package com.yadony.api.cancellation.events;

import com.yadony.api.cancellation.CancellationReason;
import java.util.UUID;

public record CancellationConfirmedEvent(
        UUID bidId,
        UUID cancellationId,
        CancellationReason reason
) {}
