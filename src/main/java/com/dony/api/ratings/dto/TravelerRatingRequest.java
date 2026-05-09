package com.dony.api.ratings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TravelerRatingRequest(
        @NotNull UUID bidId,
        @NotNull @Min(1) @Max(5) Integer stars,
        @Size(max = 200) String comment
) {}
