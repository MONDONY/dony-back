package com.dony.api.payments.cash.event;

import java.util.UUID;

public record CommissionRefundRequested(
        UUID bidId,
        UUID travelerId,
        String reason
) {}
