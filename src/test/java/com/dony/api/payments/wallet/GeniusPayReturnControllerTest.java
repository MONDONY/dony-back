package com.dony.api.payments.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Page de rebond HTTPS après le checkout GeniusPay — endpoint public
 * (aucun token Firebase), ne crédite jamais le wallet (le webhook reste
 * l'unique source de vérité).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GeniusPayReturnControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void successStatus_returnsPageWithDeepLinkToSuccess() throws Exception {
        mockMvc.perform(get("/payments/geniuspay/return").param("status", "success"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "dony://wallet/topup-return/success")));
    }

    @Test
    void errorStatus_returnsPageWithDeepLinkToError() throws Exception {
        mockMvc.perform(get("/payments/geniuspay/return").param("status", "error"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "dony://wallet/topup-return/error")));
    }

    @Test
    void unknownStatus_defaultsToError() throws Exception {
        mockMvc.perform(get("/payments/geniuspay/return").param("status", "garbage"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "dony://wallet/topup-return/error")));
    }

    @Test
    void missingStatus_defaultsToError() throws Exception {
        mockMvc.perform(get("/payments/geniuspay/return"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "dony://wallet/topup-return/error")));
    }

    @Test
    void noAuthRequired_accessibleWithoutFirebaseToken() throws Exception {
        mockMvc.perform(get("/payments/geniuspay/return").param("status", "success"))
                .andExpect(status().isOk());
    }
}
