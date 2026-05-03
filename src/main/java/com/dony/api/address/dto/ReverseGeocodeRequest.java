package com.dony.api.address.dto;

import jakarta.validation.constraints.NotNull;

public record ReverseGeocodeRequest(@NotNull Double lat, @NotNull Double lng) {}
