package com.dony.api.matching;

import com.dony.api.matching.dto.BidCheckoutRequest;
import com.dony.api.matching.dto.BidCheckoutResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BidCheckoutControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private BidCheckoutService bidCheckoutService;

    private static UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void post_checkout_returns_201_with_clientSecret() throws Exception {
        UUID bidId = UUID.randomUUID();
        BidCheckoutResponse resp = new BidCheckoutResponse(
                bidId, "pi_xxx_secret_yyy", "pk_test_zzz",
                LocalDateTime.now().plusMinutes(15));
        when(bidCheckoutService.checkout(anyString(), any(), any())).thenReturn(resp);

        BidCheckoutRequest req = new BidCheckoutRequest(
                UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("100"),
                "x", "OTHER", "n", "+221", true);

        mockMvc.perform(post("/bids/checkout")
                        .with(authentication(authenticatedAs("uid-sender")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bidId").value(bidId.toString()))
                .andExpect(jsonPath("$.clientSecret").value("pi_xxx_secret_yyy"));
    }

    @Test
    void post_checkout_with_invalid_body_returns_4xx() throws Exception {
        BidCheckoutRequest invalid = new BidCheckoutRequest(
                UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("100"),
                "x", "OTHER", "n", "+221", false);

        mockMvc.perform(post("/bids/checkout")
                        .with(authentication(authenticatedAs("uid-sender")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().is4xxClientError());
    }
}
