package com.dony.api.requests.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corps d'un signalement de demande. reason = code court (SCAM, PROHIBITED, INAPPROPRIATE, OTHER…). */
public record PackageRequestReportRequest(
    @NotBlank @Size(max = 50) String reason,
    @Size(max = 500) String details
) {}
