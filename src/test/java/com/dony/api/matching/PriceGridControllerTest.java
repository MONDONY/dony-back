package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.matching.dto.PriceGridItemRequest;
import com.dony.api.matching.dto.PriceGridItemResponse;
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

import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PriceGridControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private PriceGridService priceGridService;
    @MockBean private UserRepository userRepository;

    private static final String TRAVELER_FIREBASE_UID = "uid-traveler-test";
    private static final String SENDER_FIREBASE_UID = "uid-sender-test";
    private static final UUID TRAVELER_INTERNAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private static UsernamePasswordAuthenticationToken authenticatedAsTraveler() {
        return new UsernamePasswordAuthenticationToken(
                TRAVELER_FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static UsernamePasswordAuthenticationToken authenticatedAsSender() {
        return new UsernamePasswordAuthenticationToken(
                SENDER_FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @BeforeEach
    void setUp() {
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", TRAVELER_INTERNAL_ID);
        traveler.setFirebaseUid(TRAVELER_FIREBASE_UID);
        when(userRepository.findByFirebaseUid(TRAVELER_FIREBASE_UID)).thenReturn(Optional.of(traveler));
    }

    // ── Test 1: GET /travelers/me/price-grid returns 200 with items ───────────

    @Test
    void getMyPriceGrid_traveler_returns200WithItems() throws Exception {
        PriceGridItemResponse item = new PriceGridItemResponse(
                UUID.randomUUID(), "Valise cabine", new BigDecimal("10.00"),
                new BigDecimal("11.20"), 0);
        when(priceGridService.getItems(TRAVELER_INTERNAL_ID)).thenReturn(List.of(item));

        mockMvc.perform(get("/travelers/me/price-grid")
                        .with(authentication(authenticatedAsTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("Valise cabine"))
                .andExpect(jsonPath("$[0].unitPriceNet").value(10.00))
                .andExpect(jsonPath("$[0].unitPriceDisplay").value(11.20));
    }

    // ── Test 2: POST /travelers/me/price-grid/items returns 201 ──────────────

    @Test
    void addItem_traveler_returns201WithItem() throws Exception {
        UUID newItemId = UUID.randomUUID();
        PriceGridItemResponse created = new PriceGridItemResponse(
                newItemId, "Carton", new BigDecimal("15.00"),
                new BigDecimal("16.80"), 0);
        when(priceGridService.addItem(eq(TRAVELER_INTERNAL_ID), any(PriceGridItemRequest.class),
                eq(TRAVELER_INTERNAL_ID))).thenReturn(created);

        PriceGridItemRequest req = new PriceGridItemRequest("Carton", new BigDecimal("15.00"));

        mockMvc.perform(post("/travelers/me/price-grid/items")
                        .with(authentication(authenticatedAsTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(newItemId.toString()))
                .andExpect(jsonPath("$.label").value("Carton"))
                .andExpect(jsonPath("$.unitPriceDisplay").value(16.80));
    }

    // ── Test 3: POST /travelers/me/price-grid/items returns 403 for SENDER ───

    @Test
    void addItem_sender_returns403() throws Exception {
        PriceGridItemRequest req = new PriceGridItemRequest("Carton", new BigDecimal("15.00"));

        mockMvc.perform(post("/travelers/me/price-grid/items")
                        .with(authentication(authenticatedAsSender()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // ── Test 4: PUT /travelers/me/price-grid/items/{itemId} returns 200 ──────

    @Test
    void updateItem_traveler_returns200() throws Exception {
        UUID itemId = UUID.randomUUID();
        PriceGridItemResponse updated = new PriceGridItemResponse(
                itemId, "Valise 23kg", new BigDecimal("20.00"),
                new BigDecimal("22.40"), 0);
        when(priceGridService.updateItem(eq(TRAVELER_INTERNAL_ID), eq(itemId),
                any(PriceGridItemRequest.class), eq(TRAVELER_INTERNAL_ID))).thenReturn(updated);

        PriceGridItemRequest req = new PriceGridItemRequest("Valise 23kg", new BigDecimal("20.00"));

        mockMvc.perform(put("/travelers/me/price-grid/items/" + itemId)
                        .with(authentication(authenticatedAsTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Valise 23kg"))
                .andExpect(jsonPath("$.unitPriceDisplay").value(22.40));
    }

    // ── Test 5: DELETE /travelers/me/price-grid/items/{itemId} returns 204 ───

    @Test
    void deleteItem_traveler_returns204() throws Exception {
        UUID itemId = UUID.randomUUID();

        mockMvc.perform(delete("/travelers/me/price-grid/items/" + itemId)
                        .with(authentication(authenticatedAsTraveler())))
                .andExpect(status().isNoContent());
    }

    // ── Test 6: GET /travelers/me/price-grid returns 403 for unauthenticated ─

    @Test
    void getMyPriceGrid_unauthenticated_returns401or403() throws Exception {
        mockMvc.perform(get("/travelers/me/price-grid"))
                .andExpect(status().isUnauthorized());
    }
}
