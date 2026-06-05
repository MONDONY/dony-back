package com.dony.api.requests.dto;

import com.dony.api.payments.cash.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record NegotiationSubmitTripRequest(
    @NotNull UUID travelerAnnouncementId,
    @NotNull PaymentMethod paymentMethod,
    // CASH uniquement : si true, le voyageur consent à payer la commission sur sa
    // carte quand son wallet est insuffisant (prélevée au finalize, wallet puis
    // carte). Absent du JSON → false (wallet d'abord).
    boolean useCardForCommission
) {
    public NegotiationSubmitTripRequest(UUID travelerAnnouncementId, PaymentMethod paymentMethod) {
        this(travelerAnnouncementId, paymentMethod, false);
    }
}
