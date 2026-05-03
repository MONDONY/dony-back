package com.dony.api.auth.dto;

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
    int totalShipments
) {}
