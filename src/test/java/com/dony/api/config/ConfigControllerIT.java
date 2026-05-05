package com.dony.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /config/commission-rate}.
 *
 * <p>Uses {@code @SpringBootTest} (full application context) + {@code @AutoConfigureMockMvc}
 * so the real Servlet filter chain — including {@code FirebaseTokenFilter} and
 * {@code SecurityConfig} — runs on every request. This is NOT a false-positive: unlike
 * {@code @WebMvcTest}, the full context here includes the security filter chain, which is
 * what validates that {@code /config/**} is genuinely permit-all.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfigControllerIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void getCommissionRate_returnsConfiguredRate() throws Exception {
        // No Authorization header — real FirebaseTokenFilter + SecurityConfig filter chain runs
        mockMvc.perform(get("/config/commission-rate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rate").value(0.12));
    }

    @Test
    void getCommissionRate_isPublic_noAuthRequired() throws Exception {
        // Explicitly: no Authorization header — /config/** is permitAll() in SecurityConfig.
        // The full filter chain runs; 200 proves the endpoint is truly public, not protected.
        mockMvc.perform(get("/config/commission-rate"))
            .andExpect(status().isOk());
    }
}
