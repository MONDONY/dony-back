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
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class OrangeMoneyGateway implements MobileMoneyGateway {

    private static final Logger log = LoggerFactory.getLogger(OrangeMoneyGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MobileMoneyProperties props;

    public OrangeMoneyGateway(MobileMoneyProperties props) {
        this.props = props;
    }

    @Override
    public PaymentMethod supportedProvider() {
        return PaymentMethod.ORANGE_MONEY;
    }

    @Override
    public MobileMoneyLinkResult generatePaymentLink(MobileMoneyPaymentRequest req) {
        String ref = "om_" + UUID.randomUUID().toString().replace("-", "");
        String link = props.orangeMoney().apiUrl() + "/pay?ref=" + ref + "&phone=" + req.phoneNumber();
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(props.linkExpiryMinutes());
        log.info("OrangeMoneyGateway: generated stub link for bidId={}", req.bidId());
        return new MobileMoneyLinkResult(ref, link, expiresAt);
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String signatureHeader) {
        if (props.orangeMoney().webhookSecret() == null || props.orangeMoney().webhookSecret().isBlank()) {
            log.error("OrangeMoney webhook secret not configured — rejecting webhook");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.orangeMoney().webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = HexFormat.of().formatHex(mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("OrangeMoney webhook signature verification failed", e);
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
            return "SUCCESS".equalsIgnoreCase(node.path("status").asText());
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
