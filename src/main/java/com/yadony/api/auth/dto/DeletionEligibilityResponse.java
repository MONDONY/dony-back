package com.yadony.api.auth.dto;

/**
 * Story 9.8 — Éligibilité à la suppression de compte (lecture seule, sans effet de bord).
 * {@code blockedReasonCode} vaut {@code null} quand {@code canDelete} est {@code true},
 * sinon un des codes déjà utilisés par {@code UserService#requestDeletion} /
 * {@code AuthService#deleteImmediately} : {@code "active-transactions"} ou
 * {@code "wallet-balance-not-empty"}.
 */
public record DeletionEligibilityResponse(
        boolean canDelete,
        String blockedReasonCode
) {}
