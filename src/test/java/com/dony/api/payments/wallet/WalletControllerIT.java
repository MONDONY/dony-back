package com.dony.api.payments.wallet;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean UserRepository userRepository;

    private static final UUID USER_UUID = UUID.randomUUID();
    private static final String FIREBASE_UID = "uid-test-wallet";

    @BeforeEach
    void setUp() {
        UserEntity testUser = new UserEntity();
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(testUser, USER_UUID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(userRepository.findByFirebaseUid(anyString())).thenReturn(Optional.of(testUser));
    }

    private static UsernamePasswordAuthenticationToken authAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void getBalance_returnsZeroForNewUser() throws Exception {
        mockMvc.perform(get("/wallet/balance")
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(0))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.transactions").isArray());
    }

    @Test
    void getBalance_withPageParam_returnsOk() throws Exception {
        mockMvc.perform(get("/wallet/balance")
                .param("page", "0")
                .with(authentication(authAs(FIREBASE_UID, "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").exists())
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void topup_invalidAmount_returns422() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 0.5, "paymentMethod", "STRIPE")))
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void topup_nullAmount_returns422() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("paymentMethod", "STRIPE")))
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void topup_wave_returnsRedirectUrl() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 10.00, "paymentMethod", "WAVE")))
                .with(authentication(authAs(FIREBASE_UID, "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redirectUrl").exists())
            .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void topup_orangeMoney_returnsRedirectUrl() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 20.00, "paymentMethod", "ORANGE_MONEY")))
                .with(authentication(authAs(FIREBASE_UID, "TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redirectUrl").exists());
    }

    @Test
    void getBalance_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/wallet/balance"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void topup_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/wallet/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("amount", 10.00, "paymentMethod", "WAVE"))))
            .andExpect(status().isUnauthorized());
    }
}
