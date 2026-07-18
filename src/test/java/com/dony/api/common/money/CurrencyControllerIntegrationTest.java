package com.dony.api.common.money;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code GET /config/currencies}.
 *
 * <p>Uses {@code @SpringBootTest} (full application context) + {@code @AutoConfigureMockMvc}
 * so the real Servlet filter chain — including {@code FirebaseTokenFilter} and
 * {@code SecurityConfig} — runs on every request. This validates that {@code /config/**}
 * is genuinely permit-all.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CurrencyControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Sql("classpath:sql/insert-test-currencies.sql")
    void currenciesArePublicAndSeeded() throws Exception {
        mockMvc.perform(get("/config/currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='XOF')].minorUnit").value(0))
                .andExpect(jsonPath("$[?(@.code=='XOF')].roundingIncrement").value(5))
                .andExpect(jsonPath("$[?(@.code=='EUR')].pegRateToEur").isEmpty());
    }
}
