package com.dony.api.address;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.places")
@Validated
public record GooglePlacesProperties(
    @NotBlank String apiKey,
    String allowedCountries,
    int rateLimitPerMinute,
    int dailyQuotaAutocomplete,
    int dailyQuotaDetails,
    int dailyQuotaReverse,
    boolean blockWhenQuotaExceeded
) {}
