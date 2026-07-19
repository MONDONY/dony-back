package com.dony.api.payments.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class GeniusPaySignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(GeniusPaySignatureVerifier.class);

    private final GeniusPayProperties props;

    public GeniusPaySignatureVerifier(GeniusPayProperties props) {
        this.props = props;
    }

    public boolean verify(String rawPayload, String signatureHeader) {
        if (props.webhookSecret() == null || props.webhookSecret().isBlank()) {
            log.error("GeniusPay webhook secret not configured — rejecting webhook");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = HexFormat.of().formatHex(mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("GeniusPay webhook signature verification failed", e);
            return false;
        }
    }
}
