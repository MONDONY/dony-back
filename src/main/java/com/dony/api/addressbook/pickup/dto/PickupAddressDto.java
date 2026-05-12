package com.dony.api.addressbook.pickup.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record PickupAddressDto(
        UUID id,
        String label,
        String street,
        String postalCode,
        String city,
        String country,
        String floorApartment,
        String instructions,
        Double latitude,
        Double longitude,
        boolean isDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
