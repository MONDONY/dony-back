package com.dony.api.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de {@link ContentCategoryNormalizer} + test de cohérence Java ↔ SQL :
 * la table de correspondance Java doit rester identique au CASE de
 * {@code V171__unify_content_categories.sql} (source de vérité unique — cf. javadoc de
 * la classe).
 */
class ContentCategoryNormalizerTest {

    /** Les 9 codes enum majuscules legacy → libellé canonique. */
    private static final Map<String, String> LEGACY_CODES = new LinkedHashMap<>();
    /** Les 14 libellés legacy → libellé canonique. */
    private static final Map<String, String> LEGACY_LABELS = new LinkedHashMap<>();

    static {
        LEGACY_CODES.put("VETEMENTS", "Vêtements & tissus");
        LEGACY_CODES.put("MEDICAMENTS", "Médicaments traditionnels");
        LEGACY_CODES.put("ALIMENTATION", "Alimentation sèche");
        LEGACY_CODES.put("HIFI", "Téléphone & électronique");
        LEGACY_CODES.put("DOCUMENTS", "Documents & administratif");
        LEGACY_CODES.put("TELEPHONE", "Téléphone & électronique");
        LEGACY_CODES.put("COSMETIQUES", "Cosmétiques & parfums");
        LEGACY_CODES.put("CADEAUX", "Cadeaux & jouets");
        LEGACY_CODES.put("AUTRE", "Autre");

        LEGACY_LABELS.put("Téléphones & hi-fi", "Téléphone & électronique");
        LEGACY_LABELS.put("Matériel informatique", "Téléphone & électronique");
        LEGACY_LABELS.put("Électronique", "Téléphone & électronique");
        LEGACY_LABELS.put("Hi-fi", "Téléphone & électronique");
        LEGACY_LABELS.put("Téléphone", "Téléphone & électronique");
        LEGACY_LABELS.put("Alim. sèche", "Alimentation sèche");
        LEGACY_LABELS.put("Nourriture", "Alimentation sèche");
        LEGACY_LABELS.put("Cosmétiques", "Cosmétiques & parfums");
        LEGACY_LABELS.put("Cosmét.", "Cosmétiques & parfums");
        LEGACY_LABELS.put("Vêtements", "Vêtements & tissus");
        LEGACY_LABELS.put("Médicaments", "Médicaments traditionnels");
        LEGACY_LABELS.put("Documents", "Documents & administratif");
        LEGACY_LABELS.put("Cadeaux", "Cadeaux & jouets");
        LEGACY_LABELS.put("Autres", "Autre");
    }

    // ─── normalizeOne : les 23 valeurs legacy (9 codes + 14 libellés) ───────────────

    @Test
    void normalizeOne_mapsAll9LegacyCodes() {
        assertThat(LEGACY_CODES).hasSize(9);
        LEGACY_CODES.forEach((raw, expected) ->
                assertThat(ContentCategoryNormalizer.normalizeOne(raw))
                        .as("mapping de '%s'", raw)
                        .isEqualTo(expected));
    }

    @Test
    void normalizeOne_mapsAll14LegacyLabels() {
        assertThat(LEGACY_LABELS).hasSize(14);
        LEGACY_LABELS.forEach((raw, expected) ->
                assertThat(ContentCategoryNormalizer.normalizeOne(raw))
                        .as("mapping de '%s'", raw)
                        .isEqualTo(expected));
    }

    @Test
    void normalizeOne_isCaseInsensitive() {
        assertThat(ContentCategoryNormalizer.normalizeOne("hi-fi")).isEqualTo("Téléphone & électronique");
        assertThat(ContentCategoryNormalizer.normalizeOne("HI-FI")).isEqualTo("Téléphone & électronique");
        assertThat(ContentCategoryNormalizer.normalizeOne("Hi-Fi")).isEqualTo("Téléphone & électronique");
    }

    @Test
    void normalizeOne_trimsWhitespace() {
        assertThat(ContentCategoryNormalizer.normalizeOne("  Vêtements  ")).isEqualTo("Vêtements & tissus");
    }

    @Test
    void normalizeOne_unknownFreeTextValue_preservedWithOriginalCasing() {
        assertThat(ContentCategoryNormalizer.normalizeOne("Poissons")).isEqualTo("Poissons");
        assertThat(ContentCategoryNormalizer.normalizeOne("pOissons")).isEqualTo("pOissons");
    }

    @Test
    void normalizeOne_alreadyCanonicalValue_isUnchanged() {
        assertThat(ContentCategoryNormalizer.normalizeOne("Téléphone & électronique"))
                .isEqualTo("Téléphone & électronique");
        assertThat(ContentCategoryNormalizer.normalizeOne("Autre")).isEqualTo("Autre");
    }

    @Test
    void normalizeOne_null_returnsNull() {
        assertThat(ContentCategoryNormalizer.normalizeOne(null)).isNull();
    }

    // ─── normalizeJoined ──────────────────────────────────────────────────────────

    @Test
    void normalizeJoined_normalizesEachItem() {
        assertThat(ContentCategoryNormalizer.normalizeJoined("Hi-fi, Vêtements"))
                .isEqualTo("Téléphone & électronique, Vêtements & tissus");
    }

    @Test
    void normalizeJoined_deduplicatesPreservingFirstOccurrenceOrder() {
        assertThat(ContentCategoryNormalizer.normalizeJoined("Hi-fi, Téléphone, Vêtements"))
                .isEqualTo("Téléphone & électronique, Vêtements & tissus");
    }

    @Test
    void normalizeJoined_handlesIrregularWhitespace() {
        assertThat(ContentCategoryNormalizer.normalizeJoined(" Vêtements , Hi-fi "))
                .isEqualTo("Vêtements & tissus, Téléphone & électronique");
    }

    @Test
    void normalizeJoined_preservesUnknownFreeTextValues() {
        assertThat(ContentCategoryNormalizer.normalizeJoined("Poissons, Liquides"))
                .isEqualTo("Poissons, Liquides");
    }

    @Test
    void normalizeJoined_singleItemWithoutComma() {
        assertThat(ContentCategoryNormalizer.normalizeJoined("Hi-fi")).isEqualTo("Téléphone & électronique");
    }

    @Test
    void normalizeJoined_nullAndBlank_returnedAsIs() {
        assertThat(ContentCategoryNormalizer.normalizeJoined(null)).isNull();
        assertThat(ContentCategoryNormalizer.normalizeJoined("")).isEqualTo("");
    }

    // ─── normalizeList ────────────────────────────────────────────────────────────

    @Test
    void normalizeList_normalizesEachItem() {
        assertThat(ContentCategoryNormalizer.normalizeList(List.of("Hi-fi", "Vêtements")))
                .containsExactly("Téléphone & électronique", "Vêtements & tissus");
    }

    @Test
    void normalizeList_deduplicatesPreservingOrder() {
        assertThat(ContentCategoryNormalizer.normalizeList(List.of("Hi-fi", "Téléphone", "Vêtements")))
                .containsExactly("Téléphone & électronique", "Vêtements & tissus");
    }

    @Test
    void normalizeList_null_returnsNull() {
        assertThat(ContentCategoryNormalizer.normalizeList(null)).isNull();
    }

    @Test
    void normalizeList_empty_returnsEmpty() {
        assertThat(ContentCategoryNormalizer.normalizeList(List.of())).isEmpty();
    }

    // ─── Cohérence Java ↔ SQL (V171) ──────────────────────────────────────────────
    // Parse le CASE de V171__unify_content_categories.sql (bloc "WHEN '...' THEN '...'")
    // et vérifie qu'il définit EXACTEMENT la même table de correspondance que
    // ContentCategoryNormalizer — pour qu'une divergence future (ajout d'une clé d'un
    // seul côté, ou valeur cible différente) casse ce test plutôt que de dériver
    // silencieusement entre le SQL et l'applicatif.

    private static final Pattern WHEN_THEN =
            Pattern.compile("WHEN\\s+'((?:[^'])*)'\\s+THEN\\s+'((?:[^'])*)'");

    @Test
    void javaMapMatchesV171Sql() throws IOException {
        Map<String, String> fromSql = parseCaseMappingsFromV171();

        // Toute paire WHEN/THEN du SQL doit exister, à l'identique, côté Java.
        fromSql.forEach((legacyKeyLower, canonical) ->
                assertThat(ContentCategoryNormalizer.normalizeOne(legacyKeyLower))
                        .as("clé SQL '%s' doit mapper vers '%s' côté Java", legacyKeyLower, canonical)
                        .isEqualTo(canonical));

        // Et réciproquement : toute clé Java doit être présente dans le SQL, avec la même cible.
        ContentCategoryNormalizer.legacyToCanonicalForTesting().forEach((legacyKeyLower, canonical) -> {
            assertThat(fromSql)
                    .as("clé Java '%s' absente du CASE SQL V171", legacyKeyLower)
                    .containsKey(legacyKeyLower);
            assertThat(fromSql.get(legacyKeyLower))
                    .as("clé '%s' : cible SQL vs Java", legacyKeyLower)
                    .isEqualTo(canonical);
        });
    }

    /** Parse toutes les paires {@code WHEN '<legacy>' THEN '<canonique>'} du fichier V171, en
     *  clé minuscule (comme lower(trim(...)) côté SQL et ContentCategoryNormalizer côté Java).
     *  Les blocs du fichier partagent volontairement le même texte de CASE (copie
     *  intentionnelle) : une même clé doit donc toujours mapper vers la même valeur —
     *  toute incohérence entre deux occurrences fait échouer ce parsing/test. */
    private Map<String, String> parseCaseMappingsFromV171() throws IOException {
        String sql;
        try (InputStream in = getClass()
                .getResourceAsStream("/db/migration/V171__unify_content_categories.sql")) {
            assertThat(in).as("V171__unify_content_categories.sql introuvable sur le classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = WHEN_THEN.matcher(sql);
        while (m.find()) {
            String legacyKeyLower = m.group(1).toLowerCase(java.util.Locale.ROOT);
            String canonical = m.group(2);
            String existing = result.get(legacyKeyLower);
            assertThat(existing == null || existing.equals(canonical))
                    .as("clé SQL '%s' mappe vers des valeurs différentes selon le bloc : '%s' vs '%s'",
                            legacyKeyLower, existing, canonical)
                    .isTrue();
            result.put(legacyKeyLower, canonical);
        }
        assertThat(result).as("aucune paire WHEN/THEN trouvée dans V171 — regex cassée ?").isNotEmpty();
        return result;
    }
}
