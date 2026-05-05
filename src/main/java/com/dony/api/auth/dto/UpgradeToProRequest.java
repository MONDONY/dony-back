package com.dony.api.auth.dto;

/**
 * Request body for {@code POST /auth/me/upgrade-to-pro}.
 *
 * @param companyName Legal name of the company (optional).
 * @param siret       French SIRET number — must be exactly 14 digits if provided.
 */
public record UpgradeToProRequest(
        String companyName,
        String siret
) {}
