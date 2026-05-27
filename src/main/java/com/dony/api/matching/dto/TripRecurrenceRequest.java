package com.dony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record TripRecurrenceRequest(
        UUID sourceTemplateId,

        @NotBlank @Size(max = 100) String departureCity,
        @NotBlank @Size(max = 100) String arrivalCity,
        @NotBlank @Size(max = 20)  String transportMode,
        @NotBlank @Size(max = 20)  String capacityUnit,

        @NotNull @DecimalMin("1.0") @DecimalMax("40.0") Double availableKg,
        @NotNull @Positive @DecimalMax("500.0") Double pricePerKg,

        List<String> acceptedCategories,

        @Valid @NotNull AddressDto pickupAddress,
        @Valid @NotNull AddressDto deliveryAddress,

        @JsonFormat(pattern = "HH:mm") LocalTime departureTime,

        @NotBlank @Pattern(regexp = "[01]{7}", message = "weekdays doit être 7 caractères 0/1 (Lun..Dim)")
        String weekdays,

        @Min(1) @Max(60) Integer horizonDays,

        boolean active
) {}
