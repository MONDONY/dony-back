package com.dony.api.requests.dto;

import com.dony.api.payments.cash.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record NegotiationSubmitTripRequest(
    @NotNull UUID travelerAnnouncementId,
    @NotNull PaymentMethod paymentMethod
) {}
