package com.dony.api.requests.dto;

import com.dony.api.matching.dto.AddressDto;
import com.dony.api.payments.cash.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Body posted by the traveler to {@code POST /negotiations/{id}/create-dedicated-trip}
 * when no existing announcement matches and the traveler creates a brand-new
 * trip dedicated to that single package_request.
 *
 * Locked fields (corridor, weight, transport mode, total agreed price) are NOT
 * in this DTO — they are derived server-side from the negotiating thread and
 * its underlying package_request. Only fields the traveler can edit are listed.
 */
public record NegotiationCreateDedicatedTripRequest(
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

        @Size(max = 500, message = "La note ne peut pas dépasser 500 caractères")
        String description,

        List<String> acceptedContentTypes,

        List<String> refusedTypes,

        @NotNull(message = "Le mode de paiement est obligatoire")
        PaymentMethod paymentMethod
) {}
