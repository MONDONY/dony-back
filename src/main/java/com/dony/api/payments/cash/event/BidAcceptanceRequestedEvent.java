package com.dony.api.payments.cash.event;

import com.dony.api.payments.cash.PaymentMethod;
import java.util.UUID;

public record BidAcceptanceRequestedEvent(
        UUID bidId,
        UUID travelerId,
        PaymentMethod paymentMethod
) {}
