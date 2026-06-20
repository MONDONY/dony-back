package com.dony.api.alerts;

import com.dony.api.alerts.dto.CorridorAlertRequest;
import com.dony.api.alerts.dto.CorridorAlertResponse;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.matching.dto.MatchingRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AlertControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AlertService alertService;

    private static final String FIREBASE_UID = "uid-alert-test";

    private UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UsernamePasswordAuthenticationToken asSender() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private CorridorAlertResponse sampleResponse() {
        return new CorridorAlertResponse(UUID.randomUUID(), "Paris", "Bamako", "FR", "ML",
                null, null, null, List.of(), true, 3L, java.time.LocalDateTime.now());
    }

    @Test
    void list_asTraveler_returns200() throws Exception {
        when(alertService.list(FIREBASE_UID)).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/me/corridor-alerts").with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].departureCity").value("Paris"))
                .andExpect(jsonPath("$[0].matchCount").value(3));
    }

    @Test
    void list_asSender_returns403() throws Exception {
        mockMvc.perform(get("/me/corridor-alerts").with(authentication(asSender())))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/me/corridor-alerts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_asTraveler_returns201() throws Exception {
        when(alertService.create(eq(FIREBASE_UID), any(CorridorAlertRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departureCity").value("Paris"));
    }

    @Test
    void create_blankCity_returns422() throws Exception {
        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "", "arrivalCity", "Bamako"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void create_overCap_returns422() throws Exception {
        when(alertService.create(eq(FIREBASE_UID), any(CorridorAlertRequest.class)))
                .thenThrow(new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "alert-limit-reached", "Alert Limit Reached", "Limite atteinte."));

        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("alert-limit-reached"));
    }

    @Test
    void create_duplicate_returns409() throws Exception {
        when(alertService.create(eq(FIREBASE_UID), any(CorridorAlertRequest.class)))
                .thenThrow(new DonyBusinessException(HttpStatus.CONFLICT,
                        "alert-duplicate", "Duplicate Alert", "Doublon."));

        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("alert-duplicate"));
    }

    @Test
    void update_asTraveler_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertService.update(eq(FIREBASE_UID), eq(id), any(CorridorAlertRequest.class), eq(false)))
                .thenReturn(sampleResponse());

        mockMvc.perform(put("/me/corridor-alerts/{id}", id)
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako", "active", false))))
                .andExpect(status().isOk());
    }

    @Test
    void update_notOwner_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertService.update(eq(FIREBASE_UID), eq(id), any(CorridorAlertRequest.class), any()))
                .thenThrow(new DonyNotFoundException("Alert not found"));

        mockMvc.perform(put("/me/corridor-alerts/{id}", id)
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asTraveler_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(alertService).delete(FIREBASE_UID, id);

        mockMvc.perform(delete("/me/corridor-alerts/{id}", id)
                        .with(authentication(asTraveler())))
                .andExpect(status().isNoContent());

        verify(alertService).delete(FIREBASE_UID, id);
    }

    @Test
    void matches_asTraveler_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        MatchingRequestDto dto = new MatchingRequestDto(
                UUID.randomUUID().toString(), null, "Paris → Bamako", "2026-07-10", 0.0,
                UUID.randomUUID().toString(), "Awa K", "AK", 4.5, 3,
                3.0, "Documents", 0.0, null, "excerpt", 0, "2026-06-20T10:00:00");
        when(alertService.getMatches(FIREBASE_UID, id)).thenReturn(List.of(dto));

        mockMvc.perform(get("/me/corridor-alerts/{id}/matches", id)
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contentType").value("Documents"));
    }
}
