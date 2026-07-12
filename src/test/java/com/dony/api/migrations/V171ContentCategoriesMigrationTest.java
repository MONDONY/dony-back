package com.dony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V171 — normalisation du vocabulaire des types de contenu.
 *
 * <p>Le profil "test" tourne sur H2 avec Flyway désactivé : les migrations n'y sont jamais
 * exécutées. On démarre donc un PostgreSQL embarqué (zonky, même dépendance que le harnais
 * e2e), on migre jusqu'à V170, on sème des données legacy, puis on applique V171 et on
 * vérifie le résultat.
 */
class V171ContentCategoriesMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

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

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
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

    @Test
    void v171_normalisesLegacyValues_preservesFreeText_andIsIdempotent() throws Exception {
        Flyway upToV170 = flywayUpTo("170");
        upToV170.clean();
        upToV170.migrate();

        // --- Données legacy ---
        // announcement_accepted_types / refused_types : libellés, clé = announcement_id.
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

        // --- Migration ---
        flywayUpTo("171").migrate();

        // Libellés canoniques, doublons dédupliqués (Hi-fi et Téléphone convergent).
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactlyInAnyOrder(
                        "Téléphone & électronique", "Alimentation sèche", "Poissons");

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .containsExactlyInAnyOrder("Médicaments traditionnels", "Liquides");

        // Chaîne jointe par virgule : chaque item remplacé, texte libre préservé.
        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Vêtements & tissus, Téléphone & électronique, Poissons");

        // Code enum → libellé.
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Vêtements & tissus");

        // --- Idempotence : rejouer le corps de V171 ne doit rien changer. ---
        exec(readMigrationBody());
        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Vêtements & tissus, Téléphone & électronique, Poissons");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactlyInAnyOrder(
                        "Téléphone & électronique", "Alimentation sèche", "Poissons");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .containsExactlyInAnyOrder("Médicaments traditionnels", "Liquides");
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Vêtements & tissus");
    }

    /** Lit le corps de V171 pour tester son idempotence en le rejouant. */
    private String readMigrationBody() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/V171__unify_content_categories.sql")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
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
                        + "'" + contentCategory + "') RETURNING id");
    }

    private String seedMinimalPackageRequest(String contentCategory) throws Exception {
        String senderId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO package_requests (sender_id, departure_city, arrival_city, desired_date, "
                        + "weight_kg, parcel_size, content_category, transport_mode) VALUES ("
                        + "'" + senderId + "', 'Lyon', 'Abidjan', CURRENT_DATE + 7, "
                        + "5.00, 'MEDIUM', '" + contentCategory + "', 'PLANE') RETURNING id");
    }
}
