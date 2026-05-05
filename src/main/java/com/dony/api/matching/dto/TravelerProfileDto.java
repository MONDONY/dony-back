package com.dony.api.matching.dto;

import java.util.UUID;

public record TravelerProfileDto(
        UUID id,
        String displayName,
        String phoneNumber,
        Double averageRating,
        Integer totalTrips,
        boolean kiloPro,
        boolean isProAccount,
        boolean kycVerified
) {}
