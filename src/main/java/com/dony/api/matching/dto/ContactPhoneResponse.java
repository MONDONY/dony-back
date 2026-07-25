package com.dony.api.matching.dto;

/**
 * Numéro de la contrepartie d'un colis, renvoyé uniquement sur demande explicite
 * (l'utilisateur appuie sur « appeler »).
 *
 * <p>Le numéro ne voyage plus dans les réponses de liste : il ne quitte le serveur
 * qu'au moment où quelqu'un veut réellement téléphoner. Chaque révélation est
 * journalisée dans {@code audit_log}.
 */
public record ContactPhoneResponse(
        /** Numéro E.164 de la contrepartie, ou null si son compte n'en porte aucun. */
        String phoneNumber
) {}
