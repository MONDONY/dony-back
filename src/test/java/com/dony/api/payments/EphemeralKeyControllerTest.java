package com.dony.api.payments;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.stripe.StripeWebhookIngestService;
import com.dony.api.payments.dto.EphemeralKeyResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /payments/me/ephemeral-key — clé éphémère Stripe pour la PaymentSheet native (flutter_stripe). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EphemeralKeyControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private PaymentService paymentService;
    @MockBean  private StripeWebhookIngestService ingestService;

    private static final String SENDER_UID = "sender-firebase-uid";

    private UsernamePasswordAuthenticationToken senderAuth() {
        return new UsernamePasswordAuthenticationToken(
                SENDER_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private UsernamePasswordAuthenticationToken travelerAuth() {
        return new UsernamePasswordAuthenticationToken(
                SENDER_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    void createEphemeralKey_validRequest_returns200WithSecretAndCustomerId() throws Exception {
        when(paymentService.createEphemeralKey(eq(SENDER_UID), eq("2024-06-20")))
                .thenReturn(new EphemeralKeyResponse("ek_test_secret", "cus_test"));

        mockMvc.perform(post("/payments/me/ephemeral-key")
                        .with(authentication(senderAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stripeVersion\":\"2024-06-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ephemeralKeySecret").value("ek_test_secret"))
                .andExpect(jsonPath("$.customerId").value("cus_test"));
    }

    @Test
    void createEphemeralKey_travelerRole_returns200() throws Exception {
        // TRAVELER autorisé également : même modèle d'autorisation que /me/payment-methods
        when(paymentService.createEphemeralKey(eq(SENDER_UID), eq("2024-06-20")))
                .thenReturn(new EphemeralKeyResponse("ek_test_secret", "cus_test"));

        mockMvc.perform(post("/payments/me/ephemeral-key")
                        .with(authentication(travelerAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stripeVersion\":\"2024-06-20\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void createEphemeralKey_blankStripeVersion_returns422() throws Exception {
        mockMvc.perform(post("/payments/me/ephemeral-key")
                        .with(authentication(senderAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stripeVersion\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createEphemeralKey_missingStripeVersion_returns422() throws Exception {
        mockMvc.perform(post("/payments/me/ephemeral-key")
                        .with(authentication(senderAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createEphemeralKey_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/payments/me/ephemeral-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stripeVersion\":\"2024-06-20\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createEphemeralKey_stripeGatewayError_returns502() throws Exception {
        when(paymentService.createEphemeralKey(eq(SENDER_UID), eq("2024-06-20")))
                .thenThrow(new DonyBusinessException(HttpStatus.BAD_GATEWAY,
                        "ephemeral-key-creation-failed", "Stripe Error",
                        "Impossible de préparer la fiche de paiement. Veuillez réessayer."));

        mockMvc.perform(post("/payments/me/ephemeral-key")
                        .with(authentication(senderAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stripeVersion\":\"2024-06-20\"}"))
                .andExpect(status().isBadGateway());
    }
}
