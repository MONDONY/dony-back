package com.dony.api.matching.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BidGridItemResponse(
    UUID id,
    UUID announcementGridItemId,
    String labelSnapshot,
    BigDecimal unitPriceNetSnapshot,
    BigDecimal unitPriceDisplaySnapshot,
    int quantity,
    BigDecimal lineDisplayTotal
) {}
