package com.dony.api.payments.mobilemoney;

import com.dony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

class OrangeMoneyGatewayTest {

    private OrangeMoneyGateway gateway;
    private static final String SECRET = "test-om-secret";

    @BeforeEach
    void setUp() {
        MobileMoneyProperties props = new MobileMoneyProperties(
                new MobileMoneyProperties.ProviderConfig("key", "https://wave-stub.test", "wave-secret"),
                new MobileMoneyProperties.ProviderConfig("key", "https://om-stub.test", SECRET),
                30
        );
        gateway = new OrangeMoneyGateway(props);
    }

    @Test
    void supportedProvider_isOrangeMoney() {
        assertThat(gateway.supportedProvider()).isEqualTo(PaymentMethod.ORANGE_MONEY);
    }

    @Test
    void verifyWebhookSignature_validHmac_returnsTrue() throws Exception {
        String payload = "{\"reference\":\"om_ref_1\",\"status\":\"SUCCESS\"}";
        String sig = computeHmac(payload, SECRET);
        assertThat(gateway.verifyWebhookSignature(payload, sig)).isTrue();
    }

    @Test
    void verifyWebhookSignature_tamperedPayload_returnsFalse() throws Exception {
        String payload = "{\"reference\":\"om_ref_1\",\"status\":\"SUCCESS\"}";
        String sig = computeHmac(payload, SECRET);
        assertThat(gateway.verifyWebhookSignature("{\"tampered\":true}", sig)).isFalse();
    }

    @Test
    void verifyWebhookSignature_blankSecret_returnsFalse() {
        MobileMoneyProperties propsNoSecret = new MobileMoneyProperties(
                new MobileMoneyProperties.ProviderConfig("key", "https://wave-stub.test", "wave-secret"),
                new MobileMoneyProperties.ProviderConfig("key", "https://om-stub.test", ""),
                30
        );
        OrangeMoneyGateway gwNoSecret = new OrangeMoneyGateway(propsNoSecret);
        assertThat(gwNoSecret.verifyWebhookSignature("{}", "any-sig")).isFalse();
    }

    @Test
    void isPaymentConfirmed_success_returnsTrue() {
        assertThat(gateway.isPaymentConfirmed("{\"status\":\"SUCCESS\"}")).isTrue();
    }

    @Test
    void isPaymentConfirmed_succeeded_returnsFalse() {
        // Wave uses "SUCCEEDED" — OrangeMoney uses "SUCCESS"
        assertThat(gateway.isPaymentConfirmed("{\"status\":\"SUCCEEDED\"}")).isFalse();
    }

    @Test
    void isPaymentConfirmed_unknown_returnsFalse() {
        assertThat(gateway.isPaymentConfirmed("{\"status\":\"PENDING\"}")).isFalse();
    }

    @Test
    void extractExternalReference_returnsReferenceField() {
        assertThat(gateway.extractExternalReference("{\"reference\":\"om_ref_99\"}"))
                .isEqualTo("om_ref_99");
    }

    @Test
    void extractExternalReference_missingField_returnsNull() {
        assertThat(gateway.extractExternalReference("{}")).isNull();
    }

    @Test
    void extractFailureReason_present_returnsReason() {
        assertThat(gateway.extractFailureReason("{\"failure_reason\":\"cancelled by user\"}"))
                .isEqualTo("cancelled by user");
    }

    @Test
    void extractFailureReason_absent_returnsNull() {
        assertThat(gateway.extractFailureReason("{\"status\":\"SUCCESS\"}")).isNull();
    }

    @Test
    void generatePaymentLink_returnsLinkWithRefAndPhone() {
        MobileMoneyPaymentRequest req = new MobileMoneyPaymentRequest(
                java.util.UUID.randomUUID(), "+2250700000099", "CI",
                java.math.BigDecimal.valueOf(75), "XOF");
        MobileMoneyLinkResult result = gateway.generatePaymentLink(req);

        assertThat(result.externalReference()).startsWith("om_");
        assertThat(result.paymentLink()).contains("https://om-stub.test");
        assertThat(result.paymentLink()).contains("+2250700000099");
        assertThat(result.expiresAt()).isAfter(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    @Test
    void verifyWebhookSignature_nullSecret_returnsFalse() {
        MobileMoneyProperties propsNullSecret = new MobileMoneyProperties(
                new MobileMoneyProperties.ProviderConfig("key", "https://wave-stub.test", "wave-secret"),
                new MobileMoneyProperties.ProviderConfig("key", "https://om-stub.test", null),
                30
        );
        OrangeMoneyGateway gwNullSecret = new OrangeMoneyGateway(propsNullSecret);
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
