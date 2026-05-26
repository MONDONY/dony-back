package com.dony.api.addressbook.delivery.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record DeliveryAddressDto(
    UUID id,
    String label,
    String street,
    String city,
    String country,
    String instructions,
    Double latitude,
    Double longitude,
    boolean isDefault,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
