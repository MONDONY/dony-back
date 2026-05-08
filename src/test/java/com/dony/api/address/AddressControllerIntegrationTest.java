package com.dony.api.address;

import com.dony.api.address.dto.AutocompleteSuggestion;
import com.dony.api.address.dto.PlaceDetailsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.places.rate-limit-per-minute=1")
class AddressControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean GoogleAddressService googleAddressService;
    @Autowired @Qualifier("addressRateLimitCache") Cache<String, AtomicInteger> rateLimitCache;

    @BeforeEach
    void clearRateLimit() {
        rateLimitCache.invalidateAll();
    }

    private static UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void autocomplete_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/addresses/autocomplete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\":\"Paris\",\"sessionToken\":\"tok-123\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void autocomplete_validRequest_returns200WithSuggestions() throws Exception {
        when(googleAddressService.autocomplete(anyString(), anyString(), any(), any()))
            .thenReturn(List.of(new AutocompleteSuggestion("ChIJi...", "Paris", "France")));

        mockMvc.perform(post("/addresses/autocomplete")
            .with(authentication(authenticatedAs("uid-1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\":\"Paris\",\"sessionToken\":\"tok-123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].placeId").value("ChIJi..."))
            .andExpect(jsonPath("$[0].mainText").value("Paris"))
            .andExpect(jsonPath("$[0].secondaryText").value("France"));
    }

    @Test
    void autocomplete_rateLimitExceeded_returns429() throws Exception {
        when(googleAddressService.autocomplete(anyString(), anyString(), any(), any()))
            .thenReturn(List.of());

        // First request: counter increments to 1, 1 <= 1 passes (rate-limit-per-minute=1)
        mockMvc.perform(post("/addresses/autocomplete")
            .with(authentication(authenticatedAs("uid-rate")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\":\"Paris\",\"sessionToken\":\"tok-123\"}"))
            .andExpect(status().isOk());

        // Second request: counter increments to 2, 2 > 1 is rate limited
        mockMvc.perform(post("/addresses/autocomplete")
            .with(authentication(authenticatedAs("uid-rate")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\":\"Lyon\",\"sessionToken\":\"tok-456\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.code").value("rate-limit-exceeded"));
    }

    @Test
    void details_validRequest_returns200() throws Exception {
        when(googleAddressService.details(anyString(), anyString()))
            .thenReturn(new PlaceDetailsResponse("12 Rue Hugo, Lyon", 45.748, 4.846));

        mockMvc.perform(post("/addresses/details")
            .with(authentication(authenticatedAs("uid-2")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"placeId\":\"ChIJi...\",\"sessionToken\":\"tok-abc\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("12 Rue Hugo, Lyon"))
            .andExpect(jsonPath("$.lat").value(45.748))
            .andExpect(jsonPath("$.lng").value(4.846));
    }

    @Test
    void reverse_validRequest_returns200() throws Exception {
        when(googleAddressService.reverse(anyDouble(), anyDouble()))
            .thenReturn(Optional.of(new PlaceDetailsResponse("Dakar, Sénégal", 14.693, -17.447)));

        mockMvc.perform(post("/addresses/reverse")
            .with(authentication(authenticatedAs("uid-3")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lat\":14.693,\"lng\":-17.447}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("Dakar, Sénégal"));
    }

    @Test
    void reverse_notFound_returns404() throws Exception {
        when(googleAddressService.reverse(anyDouble(), anyDouble()))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/addresses/reverse")
            .with(authentication(authenticatedAs("uid-4")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lat\":0.0,\"lng\":0.0}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("address-not-found"));
    }

    @Test
    void autocomplete_missingQuery_returns422() throws Exception {
        mockMvc.perform(post("/addresses/autocomplete")
            .with(authentication(authenticatedAs("uid-val")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sessionToken\":\"tok-123\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.violations.query").exists());
    }

    @Test
    void autocomplete_dailyQuotaExceeded_returns429() throws Exception {
        when(googleAddressService.autocomplete(anyString(), anyString(), any(), any()))
            .thenThrow(new com.dony.api.common.DonyBusinessException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "daily-quota-exceeded",
                "Service temporairement indisponible",
                "Le service de recherche d'adresse est suspendu jusqu'a demain."
            ));

        mockMvc.perform(post("/addresses/autocomplete")
            .with(authentication(authenticatedAs("uid-quota")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\":\"Paris\",\"sessionToken\":\"tok-123\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.code").value("daily-quota-exceeded"));
    }
}
