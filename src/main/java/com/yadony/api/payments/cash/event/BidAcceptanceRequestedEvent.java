package com.yadony.api.payments.cash.event;

import com.yadony.api.payments.cash.PaymentMethod;
import java.util.UUID;

public record BidAcceptanceRequestedEvent(
        UUID bidId,
        UUID travelerId,
        PaymentMethod paymentMethod
) {}
