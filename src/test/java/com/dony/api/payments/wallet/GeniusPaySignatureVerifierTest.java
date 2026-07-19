package com.dony.api.payments.wallet;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GeniusPaySignatureVerifierTest {

    // HMAC-SHA256(timestamp + "." + payload, secret) — format réel GeniusPay
    // (guide webhook officiel, jamais le payload seul).
    private String hmac(String secret, String timestamp, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String data = timestamp + "." + payload;
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private String nowTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    @Test
    void validSignatureIsAccepted() throws Exception {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "whsec_test");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);
        String payload = "{\"event\":\"payment.success\"}";
        String timestamp = nowTimestamp();
        String signature = hmac("whsec_test", timestamp, payload);

        assertThat(verifier.verify(payload, signature, timestamp)).isTrue();
    }

    @Test
    void invalidSignatureIsRejected() {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "whsec_test");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);

        assertThat(verifier.verify("{\"event\":\"payment.success\"}", "wrong-signature", nowTimestamp()))
                .isFalse();
    }

    @Test
    void missingSecretRejectsEverything() {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);

        assertThat(verifier.verify("{}", "anything", nowTimestamp())).isFalse();
    }

    @Test
    void nullSignatureHeaderIsRejected() {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "whsec_test");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);

        assertThat(verifier.verify("{}", null, nowTimestamp())).isFalse();
    }

    @Test
    void nullTimestampHeaderIsRejected() throws Exception {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "whsec_test");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);
        String payload = "{\"event\":\"payment.success\"}";
        String signature = hmac("whsec_test", nowTimestamp(), payload);

        assertThat(verifier.verify(payload, signature, null)).isFalse();
    }

    @Test
    void staleTimestampIsRejected() throws Exception {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "whsec_test");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);
        String payload = "{\"event\":\"payment.success\"}";
        // 10 minutes dans le passé — au-delà de la tolérance de 5 minutes.
        String staleTimestamp = String.valueOf(Instant.now().getEpochSecond() - 600);
        String signature = hmac("whsec_test", staleTimestamp, payload);

        assertThat(verifier.verify(payload, signature, staleTimestamp)).isFalse();
    }
}
