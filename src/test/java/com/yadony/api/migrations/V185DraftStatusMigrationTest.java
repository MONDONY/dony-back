package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V185 — ajout de DRAFT à la contrainte CHECK du statut des demandes.
 *
 * <p>La colonne est un VARCHAR(20) sous contrainte CHECK explicite
 * (chk_pkg_req_status, cf. V57) : ajouter une valeur à l'enum Java ne suffit
 * pas, la base rejetterait l'insertion. Ce test verrouille les deux sens —
 * DRAFT accepté après migration, valeur inconnue toujours rejetée.
 */
class V185DraftStatusMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDb() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDb() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void resetSchema() {
        Flyway flywayForReset = flywayUpTo("184");
        flywayForReset.clean();
        flywayForReset.migrate();
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .cleanDisabled(false)
                .target(targetVersion)
                .load();
    }

    private void migrateTo(String target) {
        flywayUpTo(target).migrate();
    }

    /** Insère une demande minimale avec le statut demandé. */
    private void insertRequestWithStatus(String status) throws SQLException {
        UUID senderId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            // Create a user first (required by foreign key)
            st.executeUpdate("""
                INSERT INTO users
                  (id, firebase_uid, username, status, created_at, updated_at)
                VALUES
                  ('%s', '%s', '%s', 'ACTIVE', now(), now())
                """.formatted(senderId, "uid" + senderId.toString().substring(0, 8), "user" + senderId.toString().substring(0, 8)));

            // Now insert the package request
            st.executeUpdate("""
                INSERT INTO package_requests
                  (id, sender_id, departure_city, arrival_city, desired_date,
                   date_tolerance_days, weight_kg, parcel_size, content_category,
                   negotiable, transport_mode, accepted_payment_methods, status, created_at, updated_at)
                VALUES
                  ('%s', '%s', 'Paris', 'Dakar', CURRENT_DATE + 10,
                   2, 3.0, 'MEDIUM', 'Documents',
                   true, 'PLANE', '{STRIPE}', '%s', now(), now())
                """.formatted(UUID.randomUUID(), senderId, status));
        }
    }

    @Test
    void beforeV185_draftStatusIsRejected() {
        migrateTo("184");
        assertThatThrownBy(() -> insertRequestWithStatus("DRAFT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_pkg_req_status");
    }

    @Test
    void afterV185_draftStatusIsAccepted() throws Exception {
        migrateTo("185");
        insertRequestWithStatus("DRAFT");

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT count(*) FROM package_requests WHERE status = 'DRAFT'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void afterV185_existingStatusesStillAccepted() throws Exception {
        migrateTo("185");
        for (String s : new String[]{"OPEN", "NEGOTIATING", "ACCEPTED",
                                     "EXPIRED", "CANCELLED", "COMPLETED"}) {
            insertRequestWithStatus(s);
        }
    }

    @Test
    void afterV185_unknownStatusStillRejected() {
        migrateTo("185");
        assertThatThrownBy(() -> insertRequestWithStatus("PUBLISHED"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_pkg_req_status");
    }
}
