package com.dony.api.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AutocompleteRequest(
    @NotBlank @Size(max = 200) String query,
    @NotBlank String sessionToken,
    Double lat,
    Double lng
) {}
