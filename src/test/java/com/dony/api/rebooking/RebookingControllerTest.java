package com.dony.api.rebooking;

import com.dony.api.common.DonyNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RebookingControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  RebookingService rebookingService;

    private static UsernamePasswordAuthenticationToken authenticatedAsSender() {
        return new UsernamePasswordAuthenticationToken(
                "uid-sender", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static UsernamePasswordAuthenticationToken authenticatedAsTraveler() {
        return new UsernamePasswordAuthenticationToken(
                "uid-traveler", null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    void pastBookings_returns200WithList() throws Exception {
        UUID bid1 = UUID.randomUUID();
        UUID tid1 = UUID.randomUUID();

        PastBookingResponse booking = new PastBookingResponse(
            bid1, tid1, "Amadou Diallo", null,
            "Paris", "Dakar", LocalDate.of(2026, 4, 1), 3L
        );

        when(rebookingService.getPastBookings("uid-sender")).thenReturn(List.of(booking));

        mockMvc.perform(get("/senders/me/past-bookings")
                        .with(authentication(authenticatedAsSender())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].travelerName").value("Amadou Diallo"))
            .andExpect(jsonPath("$[0].departureCity").value("Paris"))
            .andExpect(jsonPath("$[0].arrivalCity").value("Dakar"))
            .andExpect(jsonPath("$[0].completedTripsWithThisTraveler").value(3));
    }

    @Test
    void pastBookings_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/senders/me/past-bookings"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void pastBookings_emptyList_returns200() throws Exception {
        when(rebookingService.getPastBookings("uid-sender")).thenReturn(List.of());

        mockMvc.perform(get("/senders/me/past-bookings")
                        .with(authentication(authenticatedAsSender())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void pastBookings_withTravelerRole_returns403() throws Exception {
        mockMvc.perform(get("/senders/me/past-bookings")
                        .with(authentication(authenticatedAsTraveler())))
            .andExpect(status().isForbidden());
    }

    @Test
    void pastBookings_serviceThrowsNotFound_returns404() throws Exception {
        when(rebookingService.getPastBookings("uid-sender"))
            .thenThrow(new DonyNotFoundException("Sender not found"));

        mockMvc.perform(get("/senders/me/past-bookings")
                        .with(authentication(authenticatedAsSender())))
            .andExpect(status().isNotFound());
    }
}
