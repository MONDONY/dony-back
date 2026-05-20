package com.dony.api.matching.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record PriceGridItemRequest(
    @NotBlank @Size(max = 100) String label,
    @NotNull @DecimalMin("0.01") BigDecimal unitPriceNet
) {}
