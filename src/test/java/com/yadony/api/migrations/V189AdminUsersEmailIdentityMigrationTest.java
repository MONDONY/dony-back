package com.yadony.api.migrations;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V189AdminUsersEmailIdentityMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void resetSchema() {
        Flyway flyway = flywayUpTo("188");
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void migratesLegacyLoginToHistoricalFirebaseEmail() throws Exception {
        UUID adminId = insertLegacyAdmin("admin.legacy");

        migrateToV189();

        assertThat(queryEmail(adminId)).isEqualTo("admin.legacy@admin.yadony.invalid");
    }

    @Test
    void rejectsCaseInsensitiveLegacyEmailCollisionsBeforeCreatingTheIndex() throws Exception {
        insertLegacyAdmin("Admin");
        insertLegacyAdmin("admin");

        assertThatThrownBy(this::migrateToV189)
                .hasMessageContaining("case-insensitive email collision");
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

    private void migrateToV189() {
        flywayUpTo("189").migrate();
    }

    private UUID insertLegacyAdmin(String login) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO admin_users
                      (id, firebase_uid, login, role, status, must_change_password,
                       permission_overrides, created_at, updated_at)
                    VALUES
                      ('%s', '%s', '%s', 'ADMIN', 'ACTIVE', true, '{}'::jsonb, now(), now())
                    """.formatted(id, "uid-" + id, login));
        }
        return id;
    }

    private String queryEmail(UUID adminId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT email FROM admin_users WHERE id = '" + adminId + "'")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
