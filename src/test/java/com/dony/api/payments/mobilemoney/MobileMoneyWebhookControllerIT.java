package com.dony.api.payments.mobilemoney;

import com.dony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MobileMoneyWebhookControllerIT {

    @Autowired private MockMvc mockMvc;
    @MockBean  private MobileMoneyPaymentService paymentService;

    @Test
    void webhook_wave_callsServiceAndReturns200() throws Exception {
        doNothing().when(paymentService).handleWebhook(eq(PaymentMethod.WAVE), anyString(), anyString());

        mockMvc.perform(post("/webhooks/mobile-money/WAVE")
                .header("X-Signature", "valid-sig")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"wave_ref_1\",\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isOk());

        verify(paymentService).handleWebhook(eq(PaymentMethod.WAVE), anyString(), eq("valid-sig"));
    }

    @Test
    void webhook_orangeMoney_callsServiceAndReturns200() throws Exception {
        doNothing().when(paymentService).handleWebhook(eq(PaymentMethod.ORANGE_MONEY), anyString(), anyString());

        mockMvc.perform(post("/webhooks/mobile-money/ORANGE_MONEY")
                .header("X-Signature", "valid-sig")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"om_ref_1\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void webhook_unknownProvider_returns400() throws Exception {
        mockMvc.perform(post("/webhooks/mobile-money/BITCOIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhook_cashProvider_returns400() throws Exception {
        mockMvc.perform(post("/webhooks/mobile-money/CASH")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
