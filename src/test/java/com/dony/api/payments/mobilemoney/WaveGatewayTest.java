package com.dony.api.payments.mobilemoney;

import com.dony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

class WaveGatewayTest {

    private WaveGateway gateway;
    private static final String SECRET = "test-wave-secret";

    @BeforeEach
    void setUp() {
        MobileMoneyProperties props = new MobileMoneyProperties(
                new MobileMoneyProperties.ProviderConfig("key", "https://wave-stub.test", SECRET),
                new MobileMoneyProperties.ProviderConfig("key", "https://om-stub.test", "om-secret"),
                30
        );
        gateway = new WaveGateway(props);
    }

    @Test
    void supportedProvider_isWave() {
        assertThat(gateway.supportedProvider()).isEqualTo(PaymentMethod.WAVE);
    }

    @Test
    void verifyWebhookSignature_validHmac_returnsTrue() throws Exception {
        String payload = "{\"reference\":\"wave_ref_1\",\"status\":\"SUCCEEDED\"}";
        String sig = computeHmac(payload, SECRET);
        assertThat(gateway.verifyWebhookSignature(payload, sig)).isTrue();
    }

    @Test
    void verifyWebhookSignature_tamperedPayload_returnsFalse() throws Exception {
        String payload = "{\"reference\":\"wave_ref_1\",\"status\":\"SUCCEEDED\"}";
        String sig = computeHmac(payload, SECRET);
        assertThat(gateway.verifyWebhookSignature("{\"tampered\":true}", sig)).isFalse();
    }

    @Test
    void verifyWebhookSignature_blankSecret_returnsFalse() {
        MobileMoneyProperties propsNoSecret = new MobileMoneyProperties(
                new MobileMoneyProperties.ProviderConfig("key", "https://wave-stub.test", ""),
                new MobileMoneyProperties.ProviderConfig("key", "https://om-stub.test", "om-secret"),
                30
        );
        WaveGateway gwNoSecret = new WaveGateway(propsNoSecret);
        assertThat(gwNoSecret.verifyWebhookSignature("{}", "any-sig")).isFalse();
    }

    @Test
    void isPaymentConfirmed_succeeded_returnsTrue() {
        assertThat(gateway.isPaymentConfirmed("{\"status\":\"SUCCEEDED\"}")).isTrue();
    }

    @Test
    void isPaymentConfirmed_success_returnsFalse() {
        // Orange Money uses "SUCCESS" — Wave uses "SUCCEEDED"
        assertThat(gateway.isPaymentConfirmed("{\"status\":\"SUCCESS\"}")).isFalse();
    }

    @Test
    void isPaymentConfirmed_unknown_returnsFalse() {
        assertThat(gateway.isPaymentConfirmed("{\"status\":\"PENDING\"}")).isFalse();
    }

    @Test
    void extractExternalReference_returnsReferenceField() {
        assertThat(gateway.extractExternalReference("{\"reference\":\"wave_ref_42\"}"))
                .isEqualTo("wave_ref_42");
    }

    @Test
    void extractExternalReference_missingField_returnsNull() {
        assertThat(gateway.extractExternalReference("{\"other\":\"value\"}")).isNull();
    }

    @Test
    void extractFailureReason_present_returnsReason() {
        assertThat(gateway.extractFailureReason("{\"failure_reason\":\"insufficient funds\"}"))
                .isEqualTo("insufficient funds");
    }

    @Test
    void extractFailureReason_absent_returnsNull() {
        assertThat(gateway.extractFailureReason("{\"status\":\"SUCCEEDED\"}")).isNull();
    }

    @Test
    void generatePaymentLink_returnsLinkWithRefAndPhone() {
        MobileMoneyPaymentRequest req = new MobileMoneyPaymentRequest(
                java.util.UUID.randomUUID(), "+2250700000001", "CI",
                java.math.BigDecimal.valueOf(50), "XOF");
        MobileMoneyLinkResult result = gateway.generatePaymentLink(req);

        assertThat(result.externalReference()).startsWith("wave_");
        assertThat(result.paymentLink()).contains("https://wave-stub.test");
        assertThat(result.paymentLink()).contains("+2250700000001");
        assertThat(result.expiresAt()).isAfter(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    @Test
    void verifyWebhookSignature_nullSecret_returnsFalse() {
        MobileMoneyProperties propsNullSecret = new MobileMoneyProperties(
                new MobileMoneyProperties.ProviderConfig("key", "https://wave-stub.test", null),
                new MobileMoneyProperties.ProviderConfig("key", "https://om-stub.test", "om-secret"),
                30
        );
        WaveGateway gwNullSecret = new WaveGateway(propsNullSecret);
        assertThat(gwNullSecret.verifyWebhookSignature("{}", "any-sig")).isFalse();
    }

    @Test
    void extractExternalReference_invalidJson_returnsNull() {
        assertThat(gateway.extractExternalReference("not-json-at-all")).isNull();
    }

    @Test
    void isPaymentConfirmed_invalidJson_returnsFalse() {
        assertThat(gateway.isPaymentConfirmed("not-json")).isFalse();
    }

    @Test
    void extractFailureReason_invalidJson_returnsNull() {
        assertThat(gateway.extractFailureReason("not-json")).isNull();
    }

    private static String computeHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
