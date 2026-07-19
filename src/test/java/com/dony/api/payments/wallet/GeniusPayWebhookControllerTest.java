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
import java.time.Instant;
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
    @Autowired private GeniusPayProperties props; // valeurs test/application-test.yml

    @MockBean private ProcessedGeniusPayEventRepository processedEventRepository;
    @MockBean private WalletTopupRequestRepository topupRequestRepository;
    @MockBean private WalletService walletService;

    private String nowTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    // Signature réelle GeniusPay : HMAC-SHA256(timestamp + "." + payload, secret).
    private String sign(String payload, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String data = timestamp + "." + payload;
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void invalidSignature_returns401() throws Exception {
        String payload = "{\"event\":\"payment.success\",\"data\":{\"reference\":\"MTX-1\"}}";

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-Webhook-Signature", "wrong")
                        .header("X-Webhook-Timestamp", nowTimestamp())
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validSignature_paymentSuccess_creditsWallet() throws Exception {
        UUID userId = UUID.randomUUID();
        String payload = "{\"event\":\"payment.success\",\"data\":{\"reference\":\"MTX-1\"}}";
        String timestamp = nowTimestamp();
        WalletTopupRequestEntity topup = new WalletTopupRequestEntity();
        topup.setUserId(userId);
        topup.setAmountEur(new BigDecimal("10.00"));
        topup.setCurrency("XOF");
        when(processedEventRepository.existsById("payment.success:MTX-1")).thenReturn(false);
        when(topupRequestRepository.findByExternalReference("MTX-1")).thenReturn(Optional.of(topup));

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-Webhook-Signature", sign(payload, timestamp))
                        .header("X-Webhook-Timestamp", timestamp)
                        .content(payload))
                .andExpect(status().isOk());

        verify(walletService).credit(eq(userId), eq(new BigDecimal("10.00")),
                eq(WalletTransactionType.TOP_UP), eq("MTX-1"), eq("geniuspay-MTX-1"));
    }

    @Test
    void replayedEvent_isNoOp() throws Exception {
        String payload = "{\"event\":\"payment.success\",\"data\":{\"reference\":\"MTX-2\"}}";
        String timestamp = nowTimestamp();
        when(processedEventRepository.existsById("payment.success:MTX-2")).thenReturn(true);

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-Webhook-Signature", sign(payload, timestamp))
                        .header("X-Webhook-Timestamp", timestamp)
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(walletService);
    }

    @Test
    void paymentFailed_marksTopupFailed() throws Exception {
        String payload = "{\"event\":\"payment.failed\",\"data\":{\"reference\":\"MTX-3\"}}";
        String timestamp = nowTimestamp();
        WalletTopupRequestEntity topup = new WalletTopupRequestEntity();
        when(processedEventRepository.existsById("payment.failed:MTX-3")).thenReturn(false);
        when(topupRequestRepository.findByExternalReference("MTX-3")).thenReturn(Optional.of(topup));

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-Webhook-Signature", sign(payload, timestamp))
                        .header("X-Webhook-Timestamp", timestamp)
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(walletService);
        verify(topupRequestRepository).save(argThat(t -> "FAILED".equals(t.getStatus())));
    }

    /**
     * Partie 1 du fix revue finale : un événement non-terminal (payment.pending) déjà vu pour une
     * référence ne doit JAMAIS bloquer le payment.success qui suit pour la MÊME référence, car la
     * clé de dédup est désormais composite ("event:reference") et non plus la référence seule.
     */
    @Test
    void pendingAlreadySeen_thenSuccess_stillCreditsWallet() throws Exception {
        UUID userId = UUID.randomUUID();
        String payload = "{\"event\":\"payment.success\",\"data\":{\"reference\":\"MTX-4\"}}";
        String timestamp = nowTimestamp();
        WalletTopupRequestEntity topup = new WalletTopupRequestEntity();
        topup.setUserId(userId);
        topup.setAmountEur(new BigDecimal("15.00"));
        topup.setCurrency("XOF");
        // Un payment.pending pour MTX-4 a déjà été traité et marqué avec sa propre clé composite.
        when(processedEventRepository.existsById("payment.pending:MTX-4")).thenReturn(true);
        // Le payment.success a une clé composite DIFFÉRENTE, jamais vue.
        when(processedEventRepository.existsById("payment.success:MTX-4")).thenReturn(false);
        when(topupRequestRepository.findByExternalReference("MTX-4")).thenReturn(Optional.of(topup));

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-Webhook-Signature", sign(payload, timestamp))
                        .header("X-Webhook-Timestamp", timestamp)
                        .content(payload))
                .andExpect(status().isOk());

        verify(walletService).credit(eq(userId), eq(new BigDecimal("15.00")),
                eq(WalletTransactionType.TOP_UP), eq("MTX-4"), eq("geniuspay-MTX-4"));
    }

    /**
     * Partie 2 du fix revue finale : défense en profondeur. Même si la clé de dédup composite
     * laissait passer un doublon (ex. libellés d'event légèrement différents entre deux webhooks
     * GeniusPay pour le même succès), un topup déjà COMPLETED ne doit jamais être re-crédité.
     */
    @Test
    void topupAlreadyCompleted_secondSuccessEvent_doesNotRecredit() throws Exception {
        String payload = "{\"event\":\"payment.success\",\"data\":{\"reference\":\"MTX-5\"}}";
        String timestamp = nowTimestamp();
        WalletTopupRequestEntity topup = new WalletTopupRequestEntity();
        topup.setUserId(UUID.randomUUID());
        topup.setAmountEur(new BigDecimal("20.00"));
        topup.setCurrency("XOF");
        topup.setStatus("COMPLETED");
        // La clé de dédup n'a pas bloqué ce webhook (ex. rejeu non détecté pour une raison quelconque).
        when(processedEventRepository.existsById("payment.success:MTX-5")).thenReturn(false);
        when(topupRequestRepository.findByExternalReference("MTX-5")).thenReturn(Optional.of(topup));

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-Webhook-Signature", sign(payload, timestamp))
                        .header("X-Webhook-Timestamp", timestamp)
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(walletService);
    }

    @Test
    void webhookTestEvent_fromDashboard_isNoOp() throws Exception {
        // Bouton "Tester" du dashboard GeniusPay : event webhook.test, pas de data.reference.
        String payload = "{\"event\":\"webhook.test\",\"data\":{\"object\":\"webhook.test\","
                + "\"message\":\"This is a test webhook\"}}";
        String timestamp = nowTimestamp();

        mockMvc.perform(post("/webhooks/genius-pay")
                        .header("X-Webhook-Signature", sign(payload, timestamp))
                        .header("X-Webhook-Timestamp", timestamp)
                        .content(payload))
                .andExpect(status().isOk());

        verifyNoInteractions(walletService);
        verifyNoInteractions(topupRequestRepository);
    }
}
