package com.dony.api.ratings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecipientRatingRequest(
        @NotBlank String trackingToken,
        @NotNull @Min(1) @Max(5) Integer stars,
        @Size(max = 200) String comment
) {}
