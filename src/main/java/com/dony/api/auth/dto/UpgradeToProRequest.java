package com.dony.api.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /auth/me/upgrade-to-pro}.
 *
 * @param companyName Legal name of the company (optional).
 * @param siret       French SIRET number — must be exactly 14 digits if provided.
 *                    SIRET format is validated by UserService (not by @Pattern here) because
 *                    UserService produces a precise RFC 7807 ProblemDetail with code
 *                    {@code invalid-siret}. A @Pattern backstop would fire first via @Valid
 *                    and return a generic violations map, losing the domain error code.
 */
public record UpgradeToProRequest(
        @Size(max = 255) String companyName,
        String siret
) {}
