package com.dony.api.city.dto;

public record PopularCorridorResponse(
    String departureCity,
    String departureCountry,
    String arrivalCity,
    String arrivalCountry
) {}
