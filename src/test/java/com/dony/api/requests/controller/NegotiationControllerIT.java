package com.dony.api.requests.controller;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.requests.dto.*;
import com.dony.api.requests.entity.NegotiationThreadStatus;
import com.dony.api.requests.service.NegotiationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NegotiationControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private NegotiationService service;
    @MockBean private UserRepository userRepository;

    private static final UUID SENDER_UUID = UUID.randomUUID();
    private static final UUID TRAVELER_UUID = UUID.randomUUID();

    @BeforeEach
    void setupAuth() {
        UserEntity sender = new UserEntity();
        UserEntity traveler = new UserEntity();
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(sender, SENDER_UUID);
            idField.set(traveler, TRAVELER_UUID);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
    }

    private static UsernamePasswordAuthenticationToken authAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private NegotiationThreadResponse fakeThread(UUID threadId, NegotiationThreadStatus status, String clientSecret) {
        return new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID, null,
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            status, new BigDecimal("30"), 1,
            LocalDateTime.now(), LocalDateTime.now(),
            List.of(), clientSecret,
            "Test T.", null, 0, null,
            "Paris", "Dakar", new BigDecimal("5"),
            "Chaka D.",
            false,  // isMyTurn
            false,  // canAccept
            false,  // canCounter
            4,      // roundsRemaining
            null    // linkedTrip
        );
    }

    @Test
    void post_start_returns201() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.start(eq(TRAVELER_UUID), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.OPEN, null));

        var req = new NegotiationStartRequest(
            UUID.randomUUID(), new BigDecimal("30"),
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, null
        );

        mockMvc.perform(post("/negotiations")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(threadId.toString()));
    }

    @Test
    void post_start_withoutTravelerRole_returns403() throws Exception {
        var req = new NegotiationStartRequest(
            UUID.randomUUID(), new BigDecimal("30"),
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, null
        );

        mockMvc.perform(post("/negotiations")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_counter_returnsThread() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.counter(eq(SENDER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.OPEN, null));

        var req = new NegotiationCounterRequest(new BigDecimal("25"), null);

        mockMvc.perform(post("/negotiations/" + threadId + "/counter")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(threadId.toString()));
    }

    @Test
    void post_accept_returnsThreadWithClientSecret() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.accept(eq(SENDER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.ACCEPTED, "pi_test_secret"));

        var req = new NegotiationAcceptRequest("Deal!");

        mockMvc.perform(post("/negotiations/" + threadId + "/accept")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentIntentClientSecret").value("pi_test_secret"))
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void accept_asTraveler_returns200() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.accept(eq(TRAVELER_UUID), eq(threadId), any()))
            .thenReturn(fakeThread(threadId, NegotiationThreadStatus.AWAITING_TRIP, null));

        var req = new NegotiationAcceptRequest(null);
        mockMvc.perform(post("/negotiations/" + threadId + "/accept")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());
    }

    @Test
    void accept_response_contains_calculated_fields() throws Exception {
        UUID threadId = UUID.randomUUID();
        // Build a fakeThread with isMyTurn=true, canAccept=true, canCounter=false, roundsRemaining=3
        NegotiationThreadResponse thread = new NegotiationThreadResponse(
            threadId, UUID.randomUUID(), TRAVELER_UUID, null,
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            NegotiationThreadStatus.OPEN, new BigDecimal("30"), 2,
            LocalDateTime.now(), LocalDateTime.now(),
            List.of(), null,
            "Test T.", null, 0, null,
            "Paris", "Dakar", new BigDecimal("5"),
            "Chaka D.",
            true,   // isMyTurn
            true,   // canAccept
            false,  // canCounter
            3,      // roundsRemaining
            null    // linkedTrip
        );
        when(service.accept(eq(SENDER_UUID), eq(threadId), any())).thenReturn(thread);

        var req = new NegotiationAcceptRequest("Deal!");
        mockMvc.perform(post("/negotiations/" + threadId + "/accept")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isMyTurn").value(true))
            .andExpect(jsonPath("$.canAccept").value(true))
            .andExpect(jsonPath("$.canCounter").value(false))
            .andExpect(jsonPath("$.roundsRemaining").value(3));
    }

    @Test
    void post_reject_returns204() throws Exception {
        UUID threadId = UUID.randomUUID();
        var req = new NegotiationRejectRequest("Trop cher");
        mockMvc.perform(post("/negotiations/" + threadId + "/reject")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNoContent());
    }

    @Test
    void counter_serviceThrowsConflict_returnsProblemDetail() throws Exception {
        UUID threadId = UUID.randomUUID();
        when(service.counter(eq(SENDER_UUID), eq(threadId), any()))
            .thenThrow(new ResponseStatusException(CONFLICT, "negotiation/not-your-turn"));

        var req = new NegotiationCounterRequest(new BigDecimal("25"), null);

        mockMvc.perform(post("/negotiations/" + threadId + "/counter")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("negotiation/not-your-turn")));
    }
}
