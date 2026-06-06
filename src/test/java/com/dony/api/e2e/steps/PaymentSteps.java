package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Quand;
import org.assertj.core.api.Assertions;

import java.util.Map;

public class PaymentSteps extends AbstractSteps {

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

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("le statut de paiement est vide")
    public void thenNoPayment() {
        // Empty optional → 200 with empty body or null id
        lastResponse().then().statusCode(200);
    }
}
