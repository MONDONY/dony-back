package com.dony.api.matching.dto;

import com.dony.api.payments.cash.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

public record AnnouncementRequest(
        @NotBlank(message = "La ville de départ est obligatoire")
        String departureCity,

        @NotBlank(message = "La ville d'arrivée est obligatoire")
        String arrivalCity,

        @NotNull(message = "La date de départ est obligatoire")
        @FutureOrPresent(message = "La date de départ ne peut pas être dans le passé")
        LocalDate departureDate,

        @JsonFormat(pattern = "HH:mm")
        LocalTime departureTime,

        @JsonFormat(pattern = "HH:mm")
        LocalTime arrivalTime,

        @Valid @NotNull(message = "L'adresse de remise est obligatoire")
        AddressDto pickupAddress,

        @Valid @NotNull(message = "L'adresse de récupération est obligatoire")
        AddressDto deliveryAddress,

        @NotNull(message = "La capacité disponible est obligatoire")
        @DecimalMin(value = "1.0", message = "La capacité doit être d'au moins 1 kg")
        BigDecimal availableKg,

        @NotNull(message = "Le prix par kg est obligatoire")
        @DecimalMin(value = "0.01", message = "Le prix doit être positif")
        BigDecimal pricePerKg,

        @NotNull(message = "Le mode de transport est obligatoire")
        com.dony.api.matching.TransportMode transportMode,

        @Size(max = 500, message = "La note ne peut pas dépasser 500 caractères")
        String description,

        List<String> acceptedContentTypes,

        List<String> refusedTypes,

        Set<PaymentMethod> acceptedPaymentMethods,

        com.dony.api.matching.CapacityUnit capacityUnit,

        com.dony.api.matching.PricingMode pricingMode
) {}
