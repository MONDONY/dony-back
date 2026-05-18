package com.dony.api.payments;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.stripe.StripeWebhookIngestService;
import com.dony.api.common.stripe.StripeWebhookSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentWebhookControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockBean StripeWebhookIngestService ingestService;

    @Test
    void webhook_returns200_andDelegates() throws Exception {
        mockMvc.perform(post("/payments/webhook")
                .header("Stripe-Signature", "t=123,v1=abc")
                .contentType(MediaType.TEXT_PLAIN)
                .content("{}"))
                .andExpect(status().isOk());
        verify(ingestService).ingest(eq("{}"), eq("t=123,v1=abc"), eq(StripeWebhookSource.PAYMENTS));
    }

    @Test
    void webhook_returns400_onInvalidSignature() throws Exception {
        doThrow(new DonyBusinessException(HttpStatus.BAD_REQUEST,
                "invalid-webhook-signature", "Webhook Error", "Signature invalide"))
                .when(ingestService).ingest(any(), any(), any());

        mockMvc.perform(post("/payments/webhook")
                .header("Stripe-Signature", "bad-sig")
                .contentType(MediaType.TEXT_PLAIN)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
