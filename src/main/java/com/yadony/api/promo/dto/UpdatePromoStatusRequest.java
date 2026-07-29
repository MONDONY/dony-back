package com.yadony.api.promo.dto;

import com.yadony.api.promo.PromoCodeStatus;
import jakarta.validation.constraints.NotNull;

public record UpdatePromoStatusRequest(
        @NotNull PromoCodeStatus status
) {}
