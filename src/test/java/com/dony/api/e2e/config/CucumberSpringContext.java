package com.dony.api.e2e.config;

import io.cucumber.spring.CucumberContextConfiguration;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@Import(E2EMockConfig.class)
public class CucumberSpringContext {

    /**
     * Replaces the real Google Places RestTemplate with a mock (stubbed per-scenario in
     * CucumberHooks) so the address subsystem runs end-to-end without any network call.
     * Declared here because @MockBean reliably replaces a named bean, unlike a plain
     * @Bean override which loses to the component-scanned AddressConfig definition.
     */
    @MockBean(name = "placesRestTemplate")
    RestTemplate placesRestTemplate;

    static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder()
                    .start();
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
}
