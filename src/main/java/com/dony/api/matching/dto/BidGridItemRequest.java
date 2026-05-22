package com.dony.api.matching.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record BidGridItemRequest(
    @NotNull UUID announcementGridItemId,
    @NotNull @Min(1) int quantity
) {}
