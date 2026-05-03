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

@SpringBootTest
@ActiveProfiles("test")
class BidEntityMigrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void v37_adds_payment_intent_columns() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE LOWER(table_name) = 'bids' " +
            "AND LOWER(column_name) IN ('payment_intent_id','awaiting_payment_expires_at')",
            Integer.class);
        assertThat(count).isEqualTo(2);
    }

    /**
     * Index assertion uses pg_indexes (PostgreSQL only).
     * Skipped on H2 (test profile) — the migration uses a partial index
     * (WHERE status = 'AWAITING_PAYMENT') which is unsupported on H2.
     * Run on a PostgreSQL profile to validate the indexes.
     */
    @Test
    void v37_adds_indexes() throws SQLException {
        assumeTrue(isPostgres(), "Skipped on non-PostgreSQL DB (H2 in test profile)");
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes " +
            "WHERE tablename = 'bids' AND indexname IN ('idx_bids_awaiting_payment','idx_bids_payment_intent')",
            Integer.class);
        assertThat(count).isEqualTo(2);
    }

    private boolean isPostgres() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        }
    }
}
