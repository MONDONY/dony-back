package com.dony.api.payments.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GeniusPayWebhookControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private GeniusPayProperties props; // valeurs test/application-test.yml — voir Step 7

    @MockBean private ProcessedGeniusPayEventRepository processedEventRepository;
    @MockBean private WalletTopupRequestRepository topupRequestRepository;
    @MockBean private WalletService walletService;

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void invalidSignature_returns401() throws Exception {
        String payload = "{\"event\":\"payment.success\",\"data\":{\"transaction\":{\"reference\":\"MTX-1\"}}}";

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-GeniusPay-Signature", "wrong")
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validSignature_paymentSuccess_creditsWallet() throws Exception {
        UUID userId = UUID.randomUUID();
        String payload = "{\"event\":\"payment.success\",\"data\":{\"transaction\":{\"reference\":\"MTX-1\"}}}";
        WalletTopupRequestEntity topup = new WalletTopupRequestEntity();
        topup.setUserId(userId);
        topup.setAmountEur(new BigDecimal("10.00"));
        topup.setCurrency("XOF");
        when(processedEventRepository.saveAndFlush(any())).thenReturn(new ProcessedGeniusPayEventEntity());
        when(topupRequestRepository.findByExternalReference("MTX-1")).thenReturn(Optional.of(topup));

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-GeniusPay-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk());

        verify(walletService).credit(eq(userId), eq(new BigDecimal("10.00")),
                eq(WalletTransactionType.TOP_UP), eq("MTX-1"), eq("geniuspay-MTX-1"));
    }

    @Test
    void replayedEvent_isNoOp() throws Exception {
        String payload = "{\"event\":\"payment.success\",\"data\":{\"transaction\":{\"reference\":\"MTX-2\"}}}";
        when(processedEventRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-GeniusPay-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(walletService);
    }

    @Test
    void paymentFailed_marksTopupFailed() throws Exception {
        String payload = "{\"event\":\"payment.failed\",\"data\":{\"transaction\":{\"reference\":\"MTX-3\"}}}";
        WalletTopupRequestEntity topup = new WalletTopupRequestEntity();
        when(processedEventRepository.saveAndFlush(any())).thenReturn(new ProcessedGeniusPayEventEntity());
        when(topupRequestRepository.findByExternalReference("MTX-3")).thenReturn(Optional.of(topup));

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-GeniusPay-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(walletService);
        verify(topupRequestRepository).save(argThat(t -> "FAILED".equals(t.getStatus())));
    }
}
