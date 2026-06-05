package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Entity↔column mapping check for the V126 surplus-capacity feature.
 * <p>
 * NOTE — this does NOT exercise the V126 SQL. Under the {@code test} profile
 * Flyway is disabled and the schema is derived from the JPA entities
 * ({@code ddl-auto: create-drop} on H2), so this only verifies that the three new
 * {@link AnnouncementEntity} columns ({@code reserved_kg}, {@code surplus_eligible},
 * {@code surplus_published}) map cleanly and the Spring context starts — the same
 * limitation as {@code BidEntityMigrationTest}. The partial index
 * ({@code WHERE surplus_published = TRUE}) is PostgreSQL-only and is skipped on H2
 * (see {@link #v126_adds_partial_index()}). The actual V126 SQL is validated only
 * when Flyway runs against PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
class SurplusMigrationIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void v126_adds_surplus_columns() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE LOWER(table_name) = 'announcements' " +
            "AND LOWER(column_name) IN ('reserved_kg','surplus_eligible','surplus_published')",
            Integer.class);
        assertThat(count).isEqualTo(3);
    }

    /**
     * Partial index assertion uses pg_indexes (PostgreSQL only).
     * Skipped on H2 (test profile) — the migration creates a partial index
     * (WHERE surplus_published = TRUE) which is unsupported on H2.
     */
    @Test
    void v126_adds_partial_index() throws SQLException {
        assumeTrue(isPostgres(), "Skipped on non-PostgreSQL DB (H2 in test profile)");
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes " +
            "WHERE tablename = 'announcements' AND indexname = 'idx_announcements_surplus_published'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private boolean isPostgres() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        }
    }
}
