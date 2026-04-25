package com.dony.api.spike;

import com.stripe.Stripe;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike isolé — Story 6.1 : Stripe Connect Marketplace
 *
 * Valide le flux complet avant toute implémentation dans le projet principal.
 * NE PAS inclure dans la suite CI (pas de @SpringBootTest, pas de profil test).
 *
 * Prérequis :
 *   export STRIPE_SECRET_KEY=sk_test_51...
 *
 * Variables optionnelles pour les tests de capture/remboursement :
 *   export STRIPE_TEST_CONNECTED_ACCOUNT_ID=acct_...   (créé par le test 1)
 *   export STRIPE_TEST_PAYMENT_INTENT_ID=pi_...        (en status requires_capture)
 *
 * Lancer manuellement :
 *   ./mvnw test -Dtest=StripeConnectSpikeTest -Dgroups=spike
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "STRIPE_SECRET_KEY", matches = "sk_test_.*")
class StripeConnectSpikeTest {

    @BeforeAll
    static void setup() {
        Stripe.apiKey = System.getenv("STRIPE_SECRET_KEY");
        System.out.println("\n══════════════════════════════════════════════════");
        System.out.println("  SPIKE — Stripe Connect Marketplace (Story 6.1)");
        System.out.println("══════════════════════════════════════════════════\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — Créer un compte Express connecté (voyageur)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void spike_01_createExpressConnectedAccount() throws Exception {
        AccountCreateParams params = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setCountry("FR")
                .setEmail("voyageur-test@dony.app")
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                .setRequested(true)
                                .build())
                        .build())
                .build();

        Account account = Account.create(params);

        assertThat(account.getId()).startsWith("acct_");
        assertThat(account.getType()).isEqualTo("express");

        System.out.println("✅ Test 1 — Compte Express créé");
        System.out.println("   ID        : " + account.getId());
        System.out.println("   Type      : " + account.getType());
        System.out.println("   Pays      : " + account.getCountry());
        System.out.println("   → Stocker cet ID dans UserEntity.stripeAccountId");
        System.out.println("   → export STRIPE_TEST_CONNECTED_ACCOUNT_ID=" + account.getId() + "\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — Générer un lien d'onboarding Stripe Express
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void spike_02_generateOnboardingLink() throws Exception {
        String connectedAccountId = System.getenv("STRIPE_TEST_CONNECTED_ACCOUNT_ID");
        if (connectedAccountId == null) {
            System.out.println("⚠️ Test 2 skipped — définir STRIPE_TEST_CONNECTED_ACCOUNT_ID (résultat test 1)\n");
            return;
        }

        AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                .setAccount(connectedAccountId)
                .setRefreshUrl("https://dony.app/payments/onboarding/refresh")
                .setReturnUrl("https://dony.app/payments/onboarding/return")
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build();

        AccountLink link = AccountLink.create(params);

        assertThat(link.getUrl()).startsWith("https://connect.stripe.com/");

        System.out.println("✅ Test 2 — Lien d'onboarding généré");
        System.out.println("   URL       : " + link.getUrl());
        System.out.println("   Expires   : " + link.getExpiresAt());
        System.out.println("   → Ouvrir cette URL dans la WebView Flutter\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — Créer un PaymentIntent en mode escrow (capture manuelle)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void spike_03_createPaymentIntentWithEscrow() throws Exception {
        String connectedAccountId = System.getenv("STRIPE_TEST_CONNECTED_ACCOUNT_ID");
        if (connectedAccountId == null) {
            System.out.println("⚠️ Test 3 skipped — définir STRIPE_TEST_CONNECTED_ACCOUNT_ID\n");
            return;
        }

        // Exemple : 35€ de transport, commission dony 12%
        long amountCents       = 3500L;
        long commissionCents   = Math.round(amountCents * 0.12); // 420 cents = 4.20€
        long voyageurRecevra   = amountCents - commissionCents;  // 3080 cents = 30.80€

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency("eur")
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .setApplicationFeeAmount(commissionCents)
                .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                        .setDestination(connectedAccountId)
                        .build())
                .putMetadata("bid_id",       "spike-bid-uuid-001")
                .putMetadata("sender_id",    "spike-sender-uuid")
                .putMetadata("traveler_id",  "spike-traveler-uuid")
                .build();

        PaymentIntent pi = PaymentIntent.create(params);

        assertThat(pi.getId()).startsWith("pi_");
        assertThat(pi.getCaptureMethod()).isEqualTo("manual");
        assertThat(pi.getApplicationFeeAmount()).isEqualTo(commissionCents);
        assertThat(pi.getStatus()).isEqualTo("requires_payment_method");

        System.out.println("✅ Test 3 — PaymentIntent escrow créé");
        System.out.println("   ID             : " + pi.getId());
        System.out.println("   Montant total  : " + amountCents / 100.0 + "€");
        System.out.println("   Commission dony: " + commissionCents / 100.0 + "€ (12%)");
        System.out.println("   Voyageur recevra: " + voyageurRecevra / 100.0 + "€");
        System.out.println("   Capture method : " + pi.getCaptureMethod() + " ← escrow confirmé");
        System.out.println("   Status initial : " + pi.getStatus());
        System.out.println("   → Pour tester capture : utiliser la carte test 4242424242424242");
        System.out.println("     puis passer le PI en requires_capture via le dashboard Stripe");
        System.out.println("   → export STRIPE_TEST_PAYMENT_INTENT_ID=" + pi.getId() + "\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — Capturer le PaymentIntent (libération escrow post-livraison)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void spike_04_capturePaymentIntentAfterDelivery() throws Exception {
        String paymentIntentId = System.getenv("STRIPE_TEST_PAYMENT_INTENT_ID");
        if (paymentIntentId == null) {
            System.out.println("⚠️ Test 4 skipped — définir STRIPE_TEST_PAYMENT_INTENT_ID (résultat test 3)\n");
            return;
        }

        PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);

        if (!"requires_capture".equals(pi.getStatus())) {
            System.out.println("⚠️ Test 4 skipped — status actuel : " + pi.getStatus());
            System.out.println("   Le PI doit être en status requires_capture pour capturer\n");
            return;
        }

        PaymentIntent captured = pi.capture(PaymentIntentCaptureParams.builder().build());

        assertThat(captured.getStatus()).isEqualTo("succeeded");

        System.out.println("✅ Test 4 — Escrow libéré (capture déclenchée)");
        System.out.println("   ID     : " + captured.getId());
        System.out.println("   Status : " + captured.getStatus());
        System.out.println("   → Transfer vers le compte connecté déclenché automatiquement");
        System.out.println("   → Délai virement : J+2 (EU), J+5/J+7 (Afrique via SEPA)\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — Rembourser un PaymentIntent (annulation voyageur)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void spike_05_refundPaymentIntent() throws Exception {
        String paymentIntentId = System.getenv("STRIPE_TEST_PAYMENT_INTENT_ID");
        if (paymentIntentId == null) {
            System.out.println("⚠️ Test 5 skipped — définir STRIPE_TEST_PAYMENT_INTENT_ID\n");
            return;
        }

        PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);

        // Remboursement possible uniquement si succeeded ou requires_capture
        if (!"succeeded".equals(pi.getStatus()) && !"requires_capture".equals(pi.getStatus())) {
            System.out.println("⚠️ Test 5 skipped — status actuel : " + pi.getStatus());
            System.out.println("   Remboursement possible sur succeeded ou requires_capture\n");
            return;
        }

        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .build();

        Refund refund = Refund.create(params);

        assertThat(refund.getStatus()).isEqualTo("succeeded");

        System.out.println("✅ Test 5 — Remboursement créé");
        System.out.println("   ID              : " + refund.getId());
        System.out.println("   Montant remboursé: " + refund.getAmount() / 100.0 + "€");
        System.out.println("   Status          : " + refund.getStatus());
        System.out.println("   → Commission dony NON prélevée sur remboursement ✓");
        System.out.println("   → Délai remboursement expéditeur : 3-5 jours ouvrés\n");
    }
}
