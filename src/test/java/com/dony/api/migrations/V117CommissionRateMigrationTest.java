package com.dony.api.migrations;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V117 (override par utilisateur + snapshot du taux) / V118 (contrainte CHECK).
 *
 * <p>Le profil test tourne sur H2 (Flyway désactivé, schéma créé par JPA DDL). On vérifie
 * donc que les colonnes attendues sont bien mappées et présentes dans
 * {@code information_schema}. Les contraintes CHECK (PostgreSQL, appliquées par Flyway)
 * sont validées en dev/staging — comme {@link BidEntityMigrationTest} pour les index.
 */
@SpringBootTest
@ActiveProfiles("test")
class V117CommissionRateMigrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void users_has_commission_rate_override_column() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE LOWER(table_name) = 'users' AND LOWER(column_name) = 'commission_rate_override'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void bids_has_commission_rate_snapshot_column() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE LOWER(table_name) = 'bids' AND LOWER(column_name) = 'commission_rate'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
