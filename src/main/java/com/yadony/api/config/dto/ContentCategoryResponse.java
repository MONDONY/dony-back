package com.yadony.api.config.dto;

/**
 * Une catégorie du catalogue canonique des types de contenu.
 *
 * @param code  clé technique stable (lookup d'icône côté client, i18n future).
 *              JAMAIS persistée en base.
 * @param label libellé d'affichage — c'est LA valeur stockée
 *              ({@code bids.content_category}, {@code announcement_accepted_types}, etc.).
 * @param emoji pictogramme d'affichage.
 */
public record ContentCategoryResponse(String code, String label, String emoji) {}
