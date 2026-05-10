package com.dony.api.requests.dto;

import com.dony.api.requests.entity.ParcelSize;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PackageRequestCreateRequest(
    @NotBlank @Size(max = 100) String departureCity,
    @NotBlank @Size(max = 100) String arrivalCity,
    @NotNull @FutureOrPresent LocalDate desiredDate,
    @Min(0) @Max(7) int dateToleranceDays,
    @NotNull @DecimalMin("0.5") @DecimalMax("30.0") BigDecimal weightKg,
    @NotNull ParcelSize parcelSize,
    @NotBlank @Size(max = 50) String contentCategory,
    @Size(max = 500) String description,
    @DecimalMin("0.0") @DecimalMax("500.0") BigDecimal targetPriceEur,
    @Size(max = 500) String photoUrl,
    @Size(max = 100) String pickupNeighborhood,
    @Size(max = 100) String deliveryNeighborhood
) {}
