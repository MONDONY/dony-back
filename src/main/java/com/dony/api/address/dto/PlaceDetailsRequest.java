package com.dony.api.address.dto;

import jakarta.validation.constraints.NotBlank;

public record PlaceDetailsRequest(@NotBlank String placeId, @NotBlank String sessionToken) {}
