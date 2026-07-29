package com.yadony.api.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normaliseur applicatif du vocabulaire des types de contenu — pendant, au moment de
 * l'écriture, de la migration {@code V171__unify_content_categories.sql} qui normalise
 * l'existant une seule fois.
 *
 * <p>V171 ne s'exécute qu'à la migration : rien ne garantit qu'un client mobile encore
 * sur une ancienne version cesse d'envoyer des libellés/codes legacy (ex. {@code "Hi-fi"})
 * juste parce que le catalogue backend a changé. Sans normalisation à l'écriture, les
 * colonnes fraîchement normalisées par V171 se re-remplissent de valeurs legacy dès la
 * prochaine création/modification — et {@code BidContentRules.assertNotRefused} (qui
 * compare des chaînes déjà normalisées côté annonce à une valeur non normalisée côté bid)
 * cesse silencieusement de matcher.
 *
 * <p><b>Source unique de vérité</b> : cette table de correspondance doit rester identique,
 * clé pour clé, au CASE SQL de {@code V171__unify_content_categories.sql} — verrouillé par
 * {@code ContentCategoryNormalizerTest#javaMapMatchesV171Sql}.
 *
 * <p>Insensible à la casse et aux espaces (clés en minuscules, comparaison sur
 * {@code trim().toLowerCase()}). Une valeur inconnue (saisie libre, ex. {@code "Poissons"})
 * est renvoyée telle quelle, casse comprise — jamais rejetée ni vidée.
 */
public final class ContentCategoryNormalizer {

    /**
     * Legacy (minuscules/trim) → libellé canonique. 21 clés uniques couvrant les 23 valeurs
     * legacy documentées dans V171 (9 codes enum + 14 libellés) : 'documents' et 'cadeaux'
     * sont partagés entre un code enum et son libellé homonyme (même clé une fois en
     * minuscules), donc une seule entrée suffit pour les deux.
     */
    private static final Map<String, String> LEGACY_TO_CANONICAL;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // 9 codes enum majuscules legacy (Flutter, ancien enum ContentCategory).
        m.put("vetements", "Vêtements & tissus");
        m.put("medicaments", "Médicaments traditionnels");
        m.put("alimentation", "Alimentation sèche");
        m.put("hifi", "Téléphone & électronique");
        m.put("documents", "Documents & administratif");
        m.put("telephone", "Téléphone & électronique");
        m.put("cosmetiques", "Cosmétiques & parfums");
        m.put("cadeaux", "Cadeaux & jouets");
        m.put("autre", "Autre");
        // 14 libellés legacy (dont 'documents' et 'cadeaux', déjà couverts ci-dessus par
        // leur code enum homonyme une fois en minuscules — cf. javadoc de classe).
        m.put("téléphones & hi-fi", "Téléphone & électronique");
        m.put("matériel informatique", "Téléphone & électronique");
        m.put("électronique", "Téléphone & électronique");
        m.put("hi-fi", "Téléphone & électronique");
        m.put("téléphone", "Téléphone & électronique");
        m.put("alim. sèche", "Alimentation sèche");
        m.put("nourriture", "Alimentation sèche");
        m.put("cosmétiques", "Cosmétiques & parfums");
        m.put("cosmét.", "Cosmétiques & parfums");
        m.put("vêtements", "Vêtements & tissus");
        m.put("médicaments", "Médicaments traditionnels");
        m.put("autres", "Autre");
        LEGACY_TO_CANONICAL = Collections.unmodifiableMap(m);
    }

    private ContentCategoryNormalizer() {
    }

    /**
     * Mappe un libellé/code legacy vers son libellé canonique. Valeur inconnue = renvoyée
     * telle quelle (la saisie libre doit continuer de fonctionner). Insensible à la casse
     * et aux espaces. {@code null} renvoie {@code null}.
     */
    public static String normalizeOne(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        String canonical = LEGACY_TO_CANONICAL.get(trimmed.toLowerCase(Locale.ROOT));
        return canonical != null ? canonical : trimmed;
    }

    /**
     * Normalise une chaîne jointe par virgule, item par item, en préservant l'ordre de
     * première occurrence et en déduplicant. {@code null}/blanc renvoyés tels quels.
     */
    public static String normalizeJoined(String rawJoined) {
        if (rawJoined == null || rawJoined.isBlank()) {
            return rawJoined;
        }
        List<String> items = new ArrayList<>();
        for (String part : rawJoined.split(",", -1)) {
            String normalized = normalizeOne(part);
            if (normalized == null) {
                continue;
            }
            String trimmed = normalized.strip();
            if (trimmed.isEmpty() || items.contains(trimmed)) {
                continue;
            }
            items.add(trimmed);
        }
        return String.join(", ", items);
    }

    /**
     * Normalise une liste de libellés (pour acceptedContentTypes / refusedTypes /
     * catégories d'alerte / catégories acceptées de trajet). Préserve l'ordre de première
     * occurrence, déduplique, ignore les entrées {@code null}/blanches. {@code null} renvoie
     * {@code null} (pas de distinction imposée entre "non fourni" et "vide" — laissée à
     * l'appelant).
     */
    public static List<String> normalizeList(List<String> raw) {
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String item : raw) {
            String normalized = normalizeOne(item);
            if (normalized == null) {
                continue;
            }
            String trimmed = normalized.strip();
            if (trimmed.isEmpty() || out.contains(trimmed)) {
                continue;
            }
            out.add(trimmed);
        }
        return out;
    }

    /**
     * Exposé pour le test de cohérence Java ↔ SQL (V171) — pas destiné à un usage
     * applicatif. Copie immuable.
     */
    public static Map<String, String> legacyToCanonicalForTesting() {
        return LEGACY_TO_CANONICAL;
    }
}
