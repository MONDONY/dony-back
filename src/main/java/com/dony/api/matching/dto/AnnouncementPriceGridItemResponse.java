package com.dony.api.matching.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AnnouncementPriceGridItemResponse(
    UUID id,
    String label,
    BigDecimal unitPriceNet,
    BigDecimal unitPriceDisplay
) {}
