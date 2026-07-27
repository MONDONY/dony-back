package com.dony.api.requests.dto;

import jakarta.validation.constraints.*;

public record PackageRequestCompleteDetailsRequest(
    @NotBlank @Size(max = 100) String recipientName,
    @NotBlank @Pattern(regexp = "\\+[1-9]\\d{6,14}") String recipientPhone,
    @Size(max = 100) String recipientCity
) {}
