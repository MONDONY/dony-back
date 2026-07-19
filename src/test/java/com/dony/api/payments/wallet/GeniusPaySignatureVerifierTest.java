package com.dony.api.payments.wallet;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GeniusPaySignatureVerifierTest {

    private String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validSignatureIsAccepted() throws Exception {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "whsec_test");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);
        String payload = "{\"event\":\"payment.success\"}";
        String signature = hmac("whsec_test", payload);

        assertThat(verifier.verify(payload, signature)).isTrue();
    }

    @Test
    void invalidSignatureIsRejected() {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "whsec_test");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);

        assertThat(verifier.verify("{\"event\":\"payment.success\"}", "wrong-signature")).isFalse();
    }

    @Test
    void missingSecretRejectsEverything() {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);

        assertThat(verifier.verify("{}", "anything")).isFalse();
    }

    @Test
    void nullSignatureHeaderIsRejected() {
        GeniusPayProperties props = new GeniusPayProperties("k", "s", "url", "whsec_test");
        GeniusPaySignatureVerifier verifier = new GeniusPaySignatureVerifier(props);

        assertThat(verifier.verify("{}", null)).isFalse();
    }
}
