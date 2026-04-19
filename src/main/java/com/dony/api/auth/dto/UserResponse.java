package com.dony.api.auth.dto;

import java.util.Set;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String phoneNumber,
    String email,
    Set<String> roles,
    String kycStatus,
    String status
) {}
