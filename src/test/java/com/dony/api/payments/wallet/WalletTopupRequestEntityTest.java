package com.dony.api.payments.wallet;

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
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V181 migration (wallet_topup_requests) actually holds in Postgres.
 *
 * <p>Deliberately NOT {@code @ActiveProfiles("test")}: the {@code test} profile runs against
 * H2 with {@code spring.flyway.enabled=false} (ddl-auto: create), so V181's migration and its
 * CHECK constraints never execute under it. This test instead boots with the {@code e2e}
 * profile against a real embedded PostgreSQL (io.zonky embedded-postgres, no Docker required —
 * same mechanism as {@code SettlementColumnsTest} / {@code CucumberSpringContext}), so Flyway
 * actually runs V1..V181 and the constraints are the genuine Postgres ones.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Import(E2EMockConfig.class)
class WalletTopupRequestEntityTest {

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

    @Autowired private WalletTopupRequestRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * wallet_topup_requests.user_id has a NOT NULL FK to users(id) (V181), so a bare
     * {@code UUID.randomUUID()} is rejected. Seed a minimal real user row instead —
     * same approach as {@code V171ContentCategoriesMigrationTest#seedMinimalUser}.
     */
    private UUID seedUser() {
        String uid = "uid-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (firebase_uid) VALUES (?) RETURNING id", UUID.class, uid);
    }

    @Test
    void persistsAndFindsByExternalReference() {
        WalletTopupRequestEntity entity = new WalletTopupRequestEntity();
        entity.setUserId(seedUser());
        entity.setProvider("WAVE");
        entity.setCountryCode("SN");
        entity.setPhoneNumber("+221771234567");
        entity.setAmountEur(new BigDecimal("10.00"));
        entity.setCurrency("XOF");
        entity.setAmountMinor(6560L);
        entity.setFxRate(new BigDecimal("655.957"));
        entity.setRateSource("PEGGED");
        entity.setExternalReference("MTX-TEST-" + UUID.randomUUID());
        repository.save(entity);

        var found = repository.findByExternalReference(entity.getExternalReference());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("PENDING");
        assertThat(found.get().getAmountMinor()).isEqualTo(6560L);
    }

    @Test
    void providerCheckConstraintRejectsUnknownValue() {
        WalletTopupRequestEntity entity = new WalletTopupRequestEntity();
        entity.setUserId(seedUser());
        entity.setProvider("PAYPAL");
        entity.setCountryCode("SN");
        entity.setPhoneNumber("+221771234567");
        entity.setAmountEur(new BigDecimal("10.00"));
        entity.setCurrency("XOF");
        entity.setAmountMinor(6560L);
        entity.setFxRate(new BigDecimal("655.957"));
        entity.setRateSource("PEGGED");

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> repository.saveAndFlush(entity));
    }
}
