package com.dony.api.payments.mobilemoney;

import com.dony.api.payments.cash.PaymentMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class WaveGateway implements MobileMoneyGateway {

    private static final Logger log = LoggerFactory.getLogger(WaveGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MobileMoneyProperties props;

    public WaveGateway(MobileMoneyProperties props) {
        this.props = props;
    }

    @Override
    public PaymentMethod supportedProvider() {
        return PaymentMethod.WAVE;
    }

    @Override
    public MobileMoneyLinkResult generatePaymentLink(MobileMoneyPaymentRequest req) {
        // Stub — replace with real Wave API call in production
        String ref = "wave_" + UUID.randomUUID().toString().replace("-", "");
        String link = props.wave().apiUrl() + "/pay?ref=" + ref + "&phone=" + req.phoneNumber();
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(props.linkExpiryMinutes());
        log.info("WaveGateway: generated stub link for bidId={}", req.bidId());
        return new MobileMoneyLinkResult(ref, link, expiresAt);
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String signatureHeader) {
        if (props.wave().webhookSecret() == null || props.wave().webhookSecret().isBlank()) {
            log.error("Wave webhook secret not configured — rejecting webhook");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.wave().webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = HexFormat.of().formatHex(mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8)));
            return computed.equalsIgnoreCase(signatureHeader);
        } catch (Exception e) {
            log.error("Wave webhook signature verification failed", e);
            return false;
        }
    }

    @Override
    public String extractExternalReference(String rawPayload) {
        try {
            return MAPPER.readTree(rawPayload).path("reference").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isPaymentConfirmed(String rawPayload) {
        try {
            JsonNode node = MAPPER.readTree(rawPayload);
            return "SUCCEEDED".equalsIgnoreCase(node.path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String extractFailureReason(String rawPayload) {
        try {
            return MAPPER.readTree(rawPayload).path("failure_reason").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
