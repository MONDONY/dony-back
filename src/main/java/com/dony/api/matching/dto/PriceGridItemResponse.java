package com.dony.api.matching.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PriceGridItemResponse(
    UUID id,
    String label,
    BigDecimal unitPriceNet,
    BigDecimal unitPriceDisplay,
    int position
) {}
