package com.dony.api.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementAddressIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    /**
     * Builds an Authentication with a String principal (Firebase UID),
     * matching what FirebaseTokenFilter puts in the SecurityContext.
     */
    private static UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    void createAnnouncement_withoutPickupAddress_returns422() throws Exception {
        var body = """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "availableKg": 10,
              "pricePerKg": 5,
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447}
            }
            """.formatted(LocalDate.now().plusDays(10));

        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void createAnnouncement_withoutDeliveryAddress_returns422() throws Exception {
        var body = """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "availableKg": 10,
              "pricePerKg": 5,
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846}
            }
            """.formatted(LocalDate.now().plusDays(10));

        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createAnnouncement_withInvalidLat_returns422() throws Exception {
        var body = """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "availableKg": 10,
              "pricePerKg": 5,
              "pickupAddress": {"label": "Lyon", "lat": 200.0, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447}
            }
            """.formatted(LocalDate.now().plusDays(10));

        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnprocessableEntity());
    }
}
