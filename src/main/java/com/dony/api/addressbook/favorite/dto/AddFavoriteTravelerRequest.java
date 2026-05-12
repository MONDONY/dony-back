package com.dony.api.addressbook.favorite.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddFavoriteTravelerRequest(
        @NotNull UUID travelerId,
        String notes
) {}
