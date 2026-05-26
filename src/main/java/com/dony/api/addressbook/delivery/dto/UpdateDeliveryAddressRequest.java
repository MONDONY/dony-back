package com.dony.api.addressbook.delivery.dto;

import jakarta.validation.constraints.*;

public record UpdateDeliveryAddressRequest(
    @NotBlank @Size(max = 50)  String label,
    @Size(max = 255)           String street,
    @NotBlank @Size(max = 100) String city,
    @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Z]{2}") String country,
    String instructions,
    Double latitude,
    Double longitude,
    boolean isDefault
) {}
