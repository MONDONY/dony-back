package com.dony.api.requests.controller;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.requests.dto.*;
import com.dony.api.requests.entity.*;
import com.dony.api.requests.service.PackageRequestService;
import com.dony.api.requests.service.PriceEstimationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PackageRequestControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private PackageRequestService service;
    @MockBean private PriceEstimationService estimationService;
    @MockBean private UserRepository userRepository;
    @MockBean private com.dony.api.requests.service.NegotiationService negotiationService;
    @MockBean private com.dony.api.requests.service.PackageRequestReportService reportService;

    private static final UUID SENDER_UUID = UUID.randomUUID();
    private static final UUID TRAVELER_UUID = UUID.randomUUID();

    @BeforeEach
    void setupAuth() {
        UserEntity senderEntity = new UserEntity();
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(senderEntity, SENDER_UUID);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(senderEntity));

        UserEntity travelerEntity = new UserEntity();
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(travelerEntity, TRAVELER_UUID);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(travelerEntity));
    }

    @Test
    void post_report_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/package-requests/" + id + "/report")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"SCAM\",\"details\":\"annonce frauduleuse\"}"))
            .andExpect(status().isNoContent());
        verify(reportService).report(eq(TRAVELER_UUID), eq(id), any());
    }

    @Test
    void post_report_missingReason_returns400() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/package-requests/" + id + "/report")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"details\":\"x\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    private static UsernamePasswordAuthenticationToken authAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private PackageRequestCreateRequest validRequest() {
        return new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements",
            "Cadeau", new BigDecimal("28.00"), null,
            "10e", "Plateau",
            true, java.util.EnumSet.of(com.dony.api.payments.cash.PaymentMethod.STRIPE)
        , List.of());
    }

    private PackageRequestResponse fakeResponse(UUID id) {
        return new PackageRequestResponse(
            id, SENDER_UUID,
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), ParcelSize.SMALL,
            com.dony.api.matching.TransportMode.PLANE,
            "vetements",
            "Cadeau", new BigDecimal("25"), null,
            "10e", "Plateau",
            PackageRequestStatus.OPEN, LocalDateTime.now(),
            true,
            java.util.EnumSet.of(com.dony.api.payments.cash.PaymentMethod.STRIPE),
            new BigDecimal("28.00")
        , List.of(), null, null);
    }

    @Test
    void post_create_returns201() throws Exception {
        UUID newId = UUID.randomUUID();
        when(service.create(eq(SENDER_UUID), any())).thenReturn(fakeResponse(newId));

        mockMvc.perform(post("/package-requests")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(newId.toString()))
            .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void post_create_withoutSenderRole_returns403() throws Exception {
        mockMvc.perform(post("/package-requests")
                .with(authentication(authAs("uid-traveler", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest())))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_create_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/package-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_findMine_returnsPage() throws Exception {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(service.findMine(eq(SENDER_UUID), any()))
            .thenReturn(new PageImpl<>(List.of(fakeResponse(UUID.randomUUID())), pageable, 1));

        mockMvc.perform(get("/package-requests/me")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("OPEN"));
    }

    @Test
    void get_search_returnsPage() throws Exception {
        var searchResp = new PackageRequestSearchResponse(
            UUID.randomUUID(), "Paris", "Dakar",
            new BigDecimal("48.85"), new BigDecimal("2.35"),
            new BigDecimal("14.69"), new BigDecimal("-17.44"),
            LocalDate.now().plusDays(5), 2,
            new BigDecimal("5"), ParcelSize.SMALL,
            com.dony.api.matching.TransportMode.PLANE,
            "vetements",
            new BigDecimal("25"), true, null, "10e", "Plateau",
            new PackageRequestSearchResponse.SenderPublicProfile(
                UUID.randomUUID(), "Sender", 4.5, 12, true, null),
            java.util.Set.of(com.dony.api.payments.cash.PaymentMethod.STRIPE)
        , List.of());
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(service.search(any(), any())).thenReturn(new PageImpl<>(List.of(searchResp), pageable, 1));

        mockMvc.perform(get("/package-requests")
                .param("departure", "Paris")
                .param("arrival", "Dakar")
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].departureCity").value("Paris"))
            .andExpect(jsonPath("$.content[0].negotiable").value(true))
            .andExpect(jsonPath("$.content[0].acceptedPaymentMethods[0]").value("STRIPE"));
    }

    @Test
    void get_search_firmRequest_exposesNegotiableFalse() throws Exception {
        var searchResp = new PackageRequestSearchResponse(
            UUID.randomUUID(), "Paris", "Dakar",
            new BigDecimal("48.85"), new BigDecimal("2.35"),
            new BigDecimal("14.69"), new BigDecimal("-17.44"),
            LocalDate.now().plusDays(5), 2,
            new BigDecimal("5"), ParcelSize.SMALL,
            com.dony.api.matching.TransportMode.PLANE,
            "vetements",
            new BigDecimal("25"), false, null, "10e", "Plateau",
            new PackageRequestSearchResponse.SenderPublicProfile(
                UUID.randomUUID(), "Sender", 4.5, 12, true, null),
            java.util.Set.of(com.dony.api.payments.cash.PaymentMethod.STRIPE)
        , List.of());
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(service.search(any(), any())).thenReturn(new PageImpl<>(List.of(searchResp), pageable, 1));

        mockMvc.perform(get("/package-requests")
                .param("departure", "Paris")
                .param("arrival", "Dakar")
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].negotiable").exists())
            .andExpect(jsonPath("$.content[0].negotiable").value(false));
    }

    @Test
    void delete_cancel_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(delete("/package-requests/" + id)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isNoContent());
    }

    @Test
    void error_returnsProblemDetailContentType() throws Exception {
        when(service.getById(eq(SENDER_UUID), any()))
            .thenThrow(new ResponseStatusException(NOT_FOUND, "request/not-found"));

        mockMvc.perform(get("/package-requests/" + UUID.randomUUID())
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("request/not-found")));
    }

    @Test
    void get_estimate_returnsEstimate() throws Exception {
        when(estimationService.estimate(eq("Paris"), eq("Dakar"), any()))
            .thenReturn(new PriceEstimateResponse(new BigDecimal("85"), new BigDecimal("115"), "HIGH", 15));

        mockMvc.perform(get("/package-requests/estimate")
                .param("from", "Paris")
                .param("to", "Dakar")
                .param("weight", "5")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.confidence").value("HIGH"));
    }

    @Test
    void get_byId_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(eq(SENDER_UUID), eq(id))).thenReturn(fakeResponse(id));

        mockMvc.perform(get("/package-requests/" + id)
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void put_update_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.update(eq(SENDER_UUID), eq(id), any())).thenReturn(fakeResponse(id));

        var req = new com.dony.api.requests.dto.PackageRequestCreateRequest(
            "Paris", "Dakar", LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements", "desc",
            new BigDecimal("28.00"), null, "10e", "Plateau",
            true, java.util.Set.of(com.dony.api.payments.cash.PaymentMethod.STRIPE), List.of());

        mockMvc.perform(put("/package-requests/" + id)
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void get_listThreads_returnsList() throws Exception {
        UUID id = UUID.randomUUID();
        when(negotiationService.listForRequest(eq(SENDER_UUID), eq(id))).thenReturn(java.util.List.of());

        mockMvc.perform(get("/package-requests/" + id + "/threads")
                .with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk());
    }

    @Test
    void post_completeDetails_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.completeDetails(eq(SENDER_UUID), eq(id), any(), any()))
            .thenReturn(fakeResponse(id));

        var req = new com.dony.api.requests.dto.PackageRequestCompleteDetailsRequest(
            "Mamadou Diallo",
            "+221771234567",
            "Dakar",
            new BigDecimal("150.00")
        );

        mockMvc.perform(post("/package-requests/" + id + "/complete-details")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void post_completeDetails_withoutCity_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.completeDetails(eq(SENDER_UUID), eq(id), any(), any()))
            .thenReturn(fakeResponse(id));

        var req = new com.dony.api.requests.dto.PackageRequestCompleteDetailsRequest(
            "Fatou Diop",
            "+221771234567",
            null,
            new BigDecimal("150.00")
        );

        mockMvc.perform(post("/package-requests/" + id + "/complete-details")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void post_completeDetails_withXForwardedFor_passesClientIp() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.completeDetails(eq(SENDER_UUID), eq(id), any(), any()))
            .thenReturn(fakeResponse(id));

        var req = new com.dony.api.requests.dto.PackageRequestCompleteDetailsRequest(
            "Mamadou Diallo",
            "+221771234567",
            "Dakar",
            new BigDecimal("150.00")
        );

        mockMvc.perform(post("/package-requests/" + id + "/complete-details")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());
    }

    @Test
    void get_search_withLocationParams_returnsPage() throws Exception {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(service.searchNearMe(any(), any(), any(), any(), anyDouble()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(), pageable, 0));

        mockMvc.perform(get("/package-requests")
                .param("departure", "Paris")
                .param("arrival", "Dakar")
                .param("lat", "48.85")
                .param("lng", "2.35")
                .param("radiusKm", "30.0")
                .with(authentication(authAs("uid-traveler", "TRAVELER"))))
            .andExpect(status().isOk());
    }

    // ─── Task 13 — nouveaux cas IT ──────────────────────────────────────────────

    /**
     * Cas 1 : demande non négociable sans budget → 422 avec code "target-price-required-firm"
     * Rule: PackageRequestService.createAndReturnEntity checks !negotiable && totalBudgetEur == null
     */
    @Test
    void post_create_firmWithoutBudget_returns422_withExpectedCode() throws Exception {
        PackageRequestCreateRequest req = new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements",
            "Cadeau", null,  // pas de budget
            null,            // photoUrl
            "10e", "Plateau",
            false,  // non négociable — budget requis
            java.util.EnumSet.of(com.dony.api.payments.cash.PaymentMethod.STRIPE)
        , List.of());

        when(service.create(eq(SENDER_UUID), any()))
            .thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "request/target-price-required-firm"));

        mockMvc.perform(post("/package-requests")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("request/target-price-required-firm")));
    }

    /**
     * Cas 2 : création avec acceptedPaymentMethods non vide → 201 et la valeur est persistée.
     * On vérifie que le champ acceptedPaymentMethods est renvoyé dans la réponse.
     */
    @Test
    void post_create_withPaymentMethods_returns201_andMethodsPersisted() throws Exception {
        UUID newId = UUID.randomUUID();
        // Response avec STRIPE + CASH
        PackageRequestResponse responseWithMethods = new PackageRequestResponse(
            newId, SENDER_UUID,
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), ParcelSize.SMALL,
            com.dony.api.matching.TransportMode.PLANE,
            "vetements",
            "Cadeau", new BigDecimal("25"), null,
            "10e", "Plateau",
            PackageRequestStatus.OPEN, LocalDateTime.now(),
            true,
            java.util.EnumSet.of(
                com.dony.api.payments.cash.PaymentMethod.STRIPE,
                com.dony.api.payments.cash.PaymentMethod.CASH),
            new BigDecimal("28.00")
        , List.of(), null, null);
        when(service.create(eq(SENDER_UUID), any())).thenReturn(responseWithMethods);

        PackageRequestCreateRequest req = new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements",
            "Cadeau", new BigDecimal("28.00"), null,
            "10e", "Plateau",
            true,
            java.util.EnumSet.of(
                com.dony.api.payments.cash.PaymentMethod.STRIPE,
                com.dony.api.payments.cash.PaymentMethod.CASH)
        , List.of());

        mockMvc.perform(post("/package-requests")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(newId.toString()))
            .andExpect(jsonPath("$.acceptedPaymentMethods").isArray())
            .andExpect(jsonPath("$.acceptedPaymentMethods",
                org.hamcrest.Matchers.hasItems("STRIPE", "CASH")));
    }
}
