package com.dony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V171 — normalisation du vocabulaire des types de contenu.
 *
 * <p>Le profil "test" tourne sur H2 avec Flyway désactivé : les migrations n'y sont jamais
 * exécutées. On démarre donc un PostgreSQL embarqué (zonky, même dépendance que le harnais
 * e2e), on migre jusqu'à V170, on sème des données legacy, puis on applique V171 et on
 * vérifie le résultat.
 *
 * <p>Chaque test repart d'une base vierge (clean + migrate jusqu'à V170 dans {@link
 * #resetSchema()}) pour éviter toute contamination entre cas (déduplication, collisions
 * accepted/refused, etc. sont sensibles aux données déjà en base).
 */
class V171ContentCategoriesMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

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

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void resetSchema() {
        Flyway upToV170 = flywayUpTo("170");
        upToV170.clean();
        upToV170.migrate();
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
    }

    private void migrateToV171() {
        flywayUpTo("171").migrate();
    }

    private List<String> queryStrings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Comme {@link #queryStrings} mais tolère une valeur NULL en base (retournée comme null). */
    private String queryNullableString(String sql) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Exécute un INSERT ... RETURNING id et renvoie l'UUID généré sous forme de chaîne. */
    private String insertReturningId(String sql) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Lit le corps de V171 pour tester son idempotence en le rejouant. */
    private String readMigrationBody() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/V171__unify_content_categories.sql")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ─── FIX 6 : couverture des 9 codes ET des 14 libellés, un cas par bras du CASE ──

    @Test
    void v171_migratesAllLegacyCodesAndLabels_onBids() throws Exception {
        String annId = seedMinimalAnnouncement();
        Map<String, String> allMappings = new LinkedHashMap<>();
        allMappings.putAll(LEGACY_CODES);
        allMappings.putAll(LEGACY_LABELS);
        assertThat(allMappings).hasSize(23);

        Map<String, String> bidIdByRaw = new LinkedHashMap<>();
        for (String raw : allMappings.keySet()) {
            bidIdByRaw.put(raw, seedMinimalBid(annId, raw));
        }

        migrateToV171();

        for (Map.Entry<String, String> e : allMappings.entrySet()) {
            String bidId = bidIdByRaw.get(e.getKey());
            assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                    .as("mapping de '%s'", e.getKey())
                    .containsExactly(e.getValue());
        }
    }

    // ─── FIX 1 : package_requests au format libellés joints par virgule ─────────────

    @Test
    void v171_migratesPackageRequests_commaJoinedLabelFormat() throws Exception {
        String reqId = seedMinimalPackageRequest("Vêtements,Médicaments");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Vêtements & tissus, Médicaments traditionnels");
    }

    @Test
    void v171_migratesPackageRequests_legacyCodeFormat() throws Exception {
        String reqId = seedMinimalPackageRequest("VETEMENTS");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Vêtements & tissus");
    }

    // ─── FIX 2 : collision accepted/refused sur la même annonce ─────────────────────

    @Test
    void v171_resolvesAcceptedRefusedCollision_keepsAcceptance() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Alim. sèche')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Nourriture')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Alimentation sèche");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .isEmpty();
    }

    @Test
    void v171_resolvesAcceptedRefusedCollision_hifiTelephoneVariant() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Hi-fi')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Téléphone'), ('" + annId + "', 'Électronique')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Téléphone & électronique");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .isEmpty();
    }

    @Test
    void v171_collisionResolution_doesNotAffectOtherAnnouncements() throws Exception {
        // Un refus qui ne collisionne pas (annonce différente de celle qui accepte) doit survivre.
        String annWithAcceptance = seedMinimalAnnouncement();
        String annWithRefusalOnly = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annWithAcceptance + "', 'Alim. sèche')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annWithRefusalOnly + "', 'Nourriture')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annWithRefusalOnly + "'"))
                .containsExactly("Alimentation sèche");
    }

    // ─── FIX 3 : déduplication de bids.content_category ──────────────────────────────

    @Test
    void v171_deduplicatesBidsContentCategory_preservingFirstOccurrenceOrder() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "Hi-fi, Téléphone");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    @Test
    void v171_deduplicatesPackageRequestsContentCategory() throws Exception {
        String reqId = seedMinimalPackageRequest("Hi-fi, Téléphone, Poissons");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Téléphone & électronique, Poissons");
    }

    // ─── FIX 5 : insensibilité à la casse ─────────────────────────────────────────────

    @Test
    void v171_isCaseInsensitive_lowercaseFreeTextMatchesLegacyLabel() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'hi-fi')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    @Test
    void v171_isCaseInsensitive_onBidsCommaJoinedString() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "HI-FI, téléphone");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    // ─── Valeurs libres préservées, casse comprise ───────────────────────────────────

    @Test
    void v171_preservesUnknownFreeTextValues_withOriginalCasing() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Poissons')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Liquides')");
        String bidId = seedMinimalBid(annId, "Poissons, Liquides");
        String reqId = seedMinimalPackageRequest("Poissons");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Poissons");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Liquides");
        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Poissons, Liquides");
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Poissons");
    }

    // ─── Cas limites ───────────────────────────────────────────────────────────────

    @Test
    void v171_handlesNullBidContentCategory() throws Exception {
        String annId = seedMinimalAnnouncement();
        String senderId = seedMinimalUser();
        String bidId = insertReturningId(
                "INSERT INTO bids (announcement_id, sender_id, weight_kg, declared_value_eur, content_category) "
                        + "VALUES ('" + annId + "', '" + senderId + "', 5.00, 50.00, NULL) RETURNING id");

        migrateToV171();

        assertThat(queryNullableString("SELECT content_category FROM bids WHERE id='" + bidId + "'")).isNull();
    }

    @Test
    void v171_handlesEmptyStringContentCategory() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "");
        String reqId = seedMinimalPackageRequest("");

        migrateToV171();

        assertThat(queryNullableString("SELECT content_category FROM bids WHERE id='" + bidId + "'")).isEqualTo("");
        assertThat(queryNullableString("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .isEqualTo("");
    }

    @Test
    void v171_handlesSingleItemWithoutComma() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "Hi-fi");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    @Test
    void v171_handlesIrregularWhitespace() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, " Vêtements , Hi-fi ");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Vêtements & tissus, Téléphone & électronique");
    }

    @Test
    void v171_alreadyCanonicalValue_isUnchangedOnFirstPass() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "Téléphone & électronique, Autre");
        String reqId = seedMinimalPackageRequest("Documents & administratif");
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Cadeaux & jouets')");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Téléphone & électronique, Autre");
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Documents & administratif");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Cadeaux & jouets");
    }

    // ─── Idempotence : rejouer le corps complet de la migration ne change rien ──────

    @Test
    void v171_isIdempotent_whenBodyIsReplayed() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Hi-fi'), "
                + "('" + annId + "', 'Téléphone'), "
                + "('" + annId + "', 'Alim. sèche'), "
                + "('" + annId + "', 'Poissons')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Médicaments'), "
                + "('" + annId + "', 'Liquides')");
        String bidId = seedMinimalBid(annId, "Vêtements, Hi-fi, Poissons");
        String reqId = seedMinimalPackageRequest("VETEMENTS");

        migrateToV171();

        List<String> acceptedAfterMigration = queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "' ORDER BY content_type");
        List<String> refusedAfterMigration = queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "' ORDER BY content_type");
        String bidAfterMigration = queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'").get(0);
        String reqAfterMigration =
                queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'").get(0);

        // Rejouer le corps complet de V171 (y compris ALTER COLUMN TYPE TEXT, idempotent lui aussi).
        exec(readMigrationBody());

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "' ORDER BY content_type"))
                .isEqualTo(acceptedAfterMigration);
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "' ORDER BY content_type"))
                .isEqualTo(refusedAfterMigration);
        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly(bidAfterMigration);
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly(reqAfterMigration);
    }

    // ─── Helpers de seed ────────────────────────────────────────────────────────
    // Le minimum de colonnes NOT NULL sans DEFAULT, déterminé en lisant le schéma réel :
    // V1 (users), V3 (announcements/bids), V34 (adresses précises announcements),
    // V35/V41 (transport_mode/total_kg announcements), V57 (package_requests),
    // V64 (transport_mode package_requests).

    private String seedMinimalUser() throws Exception {
        String uid = "uid-" + UUID.randomUUID();
        return insertReturningId(
                "INSERT INTO users (firebase_uid) VALUES ('" + uid + "') RETURNING id");
    }

    private String seedMinimalAnnouncement() throws Exception {
        String travelerId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO announcements (traveler_id, departure_city, arrival_city, departure_date, "
                        + "available_kg, price_per_kg, transport_mode, total_kg, "
                        + "pickup_address_label, pickup_lat, pickup_lng, "
                        + "delivery_address_label, delivery_lat, delivery_lng) VALUES ("
                        + "'" + travelerId + "', 'Paris', 'Dakar', CURRENT_DATE + 7, "
                        + "20.00, 15.00, 'PLANE', 20.00, "
                        + "'12 rue de Paris', 48.8566, 2.3522, "
                        + "'Plateau, Dakar', 14.6928, -17.4467) RETURNING id");
    }

    private String seedMinimalBid(String announcementId, String contentCategory) throws Exception {
        String senderId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO bids (announcement_id, sender_id, weight_kg, declared_value_eur, content_category) "
                        + "VALUES ('" + announcementId + "', '" + senderId + "', 5.00, 50.00, "
                        + "'" + contentCategory.replace("'", "''") + "') RETURNING id");
    }

    private String seedMinimalPackageRequest(String contentCategory) throws Exception {
        String senderId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO package_requests (sender_id, departure_city, arrival_city, desired_date, "
                        + "weight_kg, parcel_size, content_category, transport_mode) VALUES ("
                        + "'" + senderId + "', 'Lyon', 'Abidjan', CURRENT_DATE + 7, "
                        + "5.00, 'MEDIUM', '" + contentCategory.replace("'", "''") + "', 'PLANE') RETURNING id");
    }
}
