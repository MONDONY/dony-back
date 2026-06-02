package com.dony.api.promo.dto;

import com.dony.api.promo.PromoCodeStatus;
import jakarta.validation.constraints.NotNull;

public record UpdatePromoStatusRequest(
        @NotNull PromoCodeStatus status
) {}
