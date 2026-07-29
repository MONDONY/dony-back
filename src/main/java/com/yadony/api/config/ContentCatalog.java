package com.yadony.api.config;

import com.yadony.api.config.dto.ContentCategoryResponse;

import java.util.List;

/**
 * Catalogue canonique des types de contenu d'un colis — source de vérité unique,
 * servie aux clients par {@code GET /config/content-categories}.
 *
 * <p>Dérivé de l'analyse de 2 610 annonces du corridor Paris-Abidjan (361 mentionnant
 * explicitement un contenu). Volontairement corridor-agnostique.
 *
 * <p><b>C'est une constante, pas une configuration.</b> Le catalogue est un vocabulaire
 * produit, pas un réglage d'exploitation : le sortir en YAML l'exposerait à diverger par
 * environnement — exactement ce qui a produit les 9 listes divergentes que ce chantier
 * supprime.
 *
 * <p><b>INVARIANT : aucun libellé ne contient de virgule.</b> {@code bids.content_category}
 * encode plusieurs catégories jointes par virgule ; un libellé virgulé casserait le
 * {@code split(",")} de {@code BidContentRules} et de {@code CustomRuleConditionEvaluator}.
 * Verrouillé par {@code ContentCatalogTest}.
 */
public final class ContentCatalog {

    public static final List<ContentCategoryResponse> CATEGORIES = List.of(
            new ContentCategoryResponse("DOCUMENTS", "Documents & administratif", "📄"),
            new ContentCategoryResponse("ALIMENTATION_SECHE", "Alimentation sèche", "🍚"),
            new ContentCategoryResponse("PRODUITS_FRAIS", "Produits frais / périssables", "🐟"),
            new ContentCategoryResponse("COSMETIQUES", "Cosmétiques & parfums", "💄"),
            new ContentCategoryResponse("VETEMENTS", "Vêtements & tissus", "👗"),
            new ContentCategoryResponse("CHAUSSURES", "Chaussures", "👟"),
            new ContentCategoryResponse("MEDICAMENTS_TRADITIONNELS", "Médicaments traditionnels", "🌿"),
            new ContentCategoryResponse("ELECTRONIQUE", "Téléphone & électronique", "📱"),
            new ContentCategoryResponse("LIVRES", "Livres", "📚"),
            new ContentCategoryResponse("CADEAUX", "Cadeaux & jouets", "🎁"),
            new ContentCategoryResponse("AUTRE", "Autre", "📦"));

    private ContentCatalog() {}
}
