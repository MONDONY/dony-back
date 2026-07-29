package com.yadony.api.address.dto;

public record PlaceDetailsResponse(
    String label,
    Double lat,
    Double lng,
    String street,
    String city,
    String postalCode,
    String country
) {}
