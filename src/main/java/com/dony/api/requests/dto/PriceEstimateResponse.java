package com.dony.api.requests.dto;

import java.math.BigDecimal;

public record PriceEstimateResponse(
    BigDecimal lowEur, BigDecimal highEur,
    String confidence,
    int sampleSize
) {}
