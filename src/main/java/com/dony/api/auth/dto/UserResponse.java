package com.dony.api.auth.dto;

import com.dony.api.auth.StripeAccountStatus;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String phoneNumber,
    String email,
    String firstName,
    String lastName,
    LocalDate birthDate,
    String city,
    Set<String> roles,
    String kycStatus,
    String status,
    int totalTrips,
    int totalShipments,
    Boolean isProAccount,
    StripeAccountStatus stripeAccountStatus,
    String country,
    String bio,
    Set<String> languages,
    String transportMode,
    String avatarUrl,
    Double averageRating,
    AdminInfo admin
) {}
