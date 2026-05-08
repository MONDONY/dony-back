package com.dony.api.city.dto;

public record CitySearchResponse(
    String name,
    String countryCode,
    String countryName,
    double lat,
    double lng
) {}
