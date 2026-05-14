package com.dony.api.addressbook.pickup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePickupAddressRequest(
        @NotBlank @Size(max = 50) String label,
        @NotBlank @Size(max = 255) String street,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Z]{2}") String country,
        @Size(max = 50) String floorApartment,
        String instructions,
        Double latitude,
        Double longitude,
        boolean isDefault
) {}
