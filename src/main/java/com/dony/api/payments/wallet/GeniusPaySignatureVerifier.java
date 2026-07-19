package com.dony.api.payments.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class GeniusPaySignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(GeniusPaySignatureVerifier.class);

    // Tolérance anti-rejeu recommandée par la doc GeniusPay (X-Webhook-Timestamp).
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300;

    private final GeniusPayProperties props;

    public GeniusPaySignatureVerifier(GeniusPayProperties props) {
        this.props = props;
    }

    /**
     * Signature réelle GeniusPay : HMAC-SHA256(timestamp + "." + rawPayload, secret),
     * hex-encodé — jamais le payload seul (doc "Sécurité et Signature").
     */
    public boolean verify(String rawPayload, String signatureHeader, String timestampHeader) {
        if (props.webhookSecret() == null || props.webhookSecret().isBlank()) {
            log.error("GeniusPay webhook secret not configured — rejecting webhook");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()
                || timestampHeader == null || timestampHeader.isBlank()) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(timestampHeader);
            if (Math.abs(Instant.now().getEpochSecond() - timestamp) > TIMESTAMP_TOLERANCE_SECONDS) {
                log.warn("GeniusPay webhook timestamp trop ancien/futur : {}", timestampHeader);
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        try {
            String data = timestampHeader + "." + rawPayload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("GeniusPay webhook signature verification failed", e);
            return false;
        }
    }
}
