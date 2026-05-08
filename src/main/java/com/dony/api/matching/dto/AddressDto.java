package com.dony.api.matching.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddressDto(
        @NotBlank(message = "Le libellé de l'adresse est obligatoire")
        String label,

        @NotNull(message = "La latitude est obligatoire")
        @DecimalMin(value = "-90.0", message = "Latitude invalide (min -90)")
        @DecimalMax(value = "90.0",  message = "Latitude invalide (max 90)")
        Double lat,

        @NotNull(message = "La longitude est obligatoire")
        @DecimalMin(value = "-180.0", message = "Longitude invalide (min -180)")
        @DecimalMax(value = "180.0",  message = "Longitude invalide (max 180)")
        Double lng
) {}
