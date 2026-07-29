package com.yadony.api.address.dto;

import jakarta.validation.constraints.NotNull;

public record ReverseGeocodeRequest(@NotNull Double lat, @NotNull Double lng) {}
