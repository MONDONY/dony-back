package com.dony.api.payments;

import com.dony.api.e2e.config.E2EMockConfig;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V177 "all-or-none" CHECK constraint (chk_settlement_all_or_none) actually
 * holds in Postgres after the backfill runs.
 *
 * <p>Deliberately NOT {@code @ActiveProfiles("test")}: the {@code test} profile runs against
 * H2 with {@code spring.flyway.enabled=false} (ddl-auto: create) — see
 * {@code CurrencyRepositoryTest}'s note from Task 1. Under that profile V177's migration,
 * its CHECK constraint and its backfill UPDATE never execute, so asserting on them would
 * prove nothing about the real SQL. This test instead boots with the {@code e2e} profile
 * against a real embedded PostgreSQL (io.zonky embedded-postgres, no Docker required — same
 * mechanism as {@code CucumberSpringContext}), so Flyway actually runs V1..V178 and the
 * constraint is the genuine Postgres one.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Import(E2EMockConfig.class)
class SettlementColumnsTest {

    @MockBean(name = "placesRestTemplate")
    RestTemplate placesRestTemplate;

    static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired private JdbcTemplate jdbc;

    @Test
    void backfilledRowsAreAllOrNone() {
        Integer partial = jdbc.queryForObject("""
            SELECT count(*) FROM payments
             WHERE NOT (
               (settlement_currency IS NULL) = (settlement_amount_minor IS NULL)
               AND (settlement_currency IS NULL) = (settlement_rate_source IS NULL)
               AND (settlement_currency IS NULL) = (settlement_fx_rate IS NULL)
             )
            """, Integer.class);
        assertThat(partial).isZero();
    }
}
