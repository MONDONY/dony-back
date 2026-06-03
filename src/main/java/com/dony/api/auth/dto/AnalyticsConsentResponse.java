package com.dony.api.auth.dto;

/**
 * Réponse du consentement analytics RGPD (source de vérité backend).
 *
 * @param granted       null si l'utilisateur n'a jamais répondu, sinon true/false
 * @param consentAt     ISO-8601 ({@code Instant.toString()}), null si jamais répondu
 * @param policyVersion version de la politique consentie, null si jamais répondu
 */
public record AnalyticsConsentResponse(
        Boolean granted,
        String consentAt,
        String policyVersion) {}
