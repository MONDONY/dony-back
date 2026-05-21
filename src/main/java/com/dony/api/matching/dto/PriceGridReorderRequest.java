package com.dony.api.matching.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record PriceGridReorderRequest(
    @NotNull List<@NotNull UUID> orderedIds
) {}
