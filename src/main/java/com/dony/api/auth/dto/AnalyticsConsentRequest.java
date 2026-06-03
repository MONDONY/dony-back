package com.dony.api.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Requête de mise à jour du consentement analytics RGPD.
 *
 * @param granted       requis — true (consenti) / false (refusé)
 * @param policyVersion optionnel — version de la politique affichée à l'utilisateur (preuve RGPD)
 * @param source        optionnel — origine : {@code manual} | {@code auto_non_gdpr} | {@code settings} | {@code sync}
 */
public record AnalyticsConsentRequest(
        @NotNull Boolean granted,
        @Size(max = 32) String policyVersion,
        @Size(max = 32) String source) {}
