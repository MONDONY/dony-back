package com.dony.api.matching.dto;

import java.math.BigDecimal;

public record MarketPriceResponse(
        BigDecimal median,
        String currency
) {}
