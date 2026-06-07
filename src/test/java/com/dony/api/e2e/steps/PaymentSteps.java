package com.dony.api.e2e.steps;

import com.dony.api.common.stripe.StripeEventInboxRepository;
import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Quand;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

public class PaymentSteps extends AbstractSteps {

    @Autowired
    private StripeEventInboxRepository stripeEventInboxRepository;

    @Value("${stripe.webhook.payments-secret}")
    private String paymentsWebhookSecret;

    // ── When ──────────────────────────────────────────────────────────────────

    @Quand("je demande le statut de paiement de l'offre {string}")
    public void whenGetPaymentStatus(String bidAlias) {
        store(asCurrentUser().get("/payments/bid/{id}", ctx.getId(bidAlias)));
    }

    @Quand("je tente de créer un paiement pour l'offre {string}")
    public void whenCreatePayment(String bidAlias) {
        store(asCurrentUser().body(Map.of("bidId", ctx.getId(bidAlias).toString()))
                .post("/payments"));
    }

    @Quand("je consulte le statut de mon compte Stripe Connect")
    public void whenGetConnectStatus() {
        store(asCurrentUser().get("/payments/connect/account"));
    }

    @Quand("je crée mon compte Stripe Connect")
    public void whenCreateConnectAccount() {
        store(asCurrentUser().post("/payments/connect/account"));
    }

    @Quand("je génère mon lien d'onboarding Stripe")
    public void whenCreateOnboardingLink() {
        store(asCurrentUser().post("/payments/connect/onboarding-link"));
    }

    @Quand("je rafraîchis mon compte Stripe Connect")
    public void whenRefreshConnect() {
        store(asCurrentUser().post("/payments/connect/refresh"));
    }

    @Quand("j'envoie un webhook Stripe avec une signature invalide")
    public void whenWebhookBadSignature() {
        store(asPublic()
                .header("Stripe-Signature", "t=0,v1=bad")
                .body("{\"type\":\"payment_intent.succeeded\"}")
                .post("/payments/webhook"));
    }

    /**
     * Forge un webhook Stripe correctement signé et le POST sur /payments/webhook.
     * Reproduit le schéma de signature Stripe (t=&lt;ts&gt;,v1=HMAC_SHA256(ts.payload))
     * avec le secret e2e, afin que {@code StripeWebhookIngestService.ingest} l'accepte
     * et exerce sa garde d'idempotence (dédoublonnage par event id dans l'inbox).
     */
    @Quand("le webhook Stripe de type {string} et d'identifiant {string} est reçu")
    public void whenSignedWebhookReceived(String type, String eventId) {
        String payload = "{\"id\":\"" + eventId + "\",\"object\":\"event\","
                + "\"type\":\"" + type + "\","
                + "\"data\":{\"object\":{\"id\":\"pi_e2e\",\"object\":\"payment_intent\"}}}";
        store(asPublic()
                .header("Stripe-Signature", stripeSignature(payload))
                .body(payload)
                .post("/payments/webhook"));
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("le statut de paiement est vide")
    public void thenNoPayment() {
        // Empty optional → 200 with empty body or null id
        lastResponse().then().statusCode(200);
    }

    @Alors("l'inbox Stripe contient {int} évènement")
    public void thenInboxCount(int expected) {
        Assertions.assertThat(stripeEventInboxRepository.count()).isEqualTo((long) expected);
    }

    @Alors("l'évènement Stripe {string} a été ingéré")
    public void thenEventIngested(String eventId) {
        Assertions.assertThat(stripeEventInboxRepository.existsById(eventId)).isTrue();
    }

    /** Builds a valid {@code Stripe-Signature} header for {@code payload} using the e2e secret. */
    private String stripeSignature(String payload) {
        try {
            long t = Instant.now().getEpochSecond();
            String signedPayload = t + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(paymentsWebhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String v1 = HexFormat.of().formatHex(
                    mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
            return "t=" + t + ",v1=" + v1;
        } catch (Exception e) {
            throw new RuntimeException("Failed to forge Stripe signature", e);
        }
    }
}
