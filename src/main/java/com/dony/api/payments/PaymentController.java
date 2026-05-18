package com.dony.api.payments;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.stripe.StripeWebhookIngestService;
import com.dony.api.common.stripe.StripeWebhookSource;
import com.dony.api.payments.dto.ConnectAccountResponse;
import com.dony.api.payments.dto.CreatePaymentRequest;
import com.dony.api.payments.dto.OnboardingLinkResponse;
import com.dony.api.payments.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final StripeWebhookIngestService ingestService;

    public PaymentController(PaymentService paymentService,
                             StripeWebhookIngestService ingestService) {
        this.paymentService = paymentService;
        this.ingestService = ingestService;
    }

    // Story 6.2 — Lire l'état du compte Stripe Connect (lecture seule depuis la DB)
    @GetMapping("/connect/account")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<ConnectAccountResponse> getConnectAccount() {
        return ResponseEntity.ok(paymentService.getConnectAccountStatus(requireFirebaseUid()));
    }

    // Story 6.2 — Créer un compte Stripe Express pour le voyageur
    @PostMapping("/connect/account")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<ConnectAccountResponse> createConnectAccount() {
        return ResponseEntity.ok(paymentService.createConnectAccount(requireFirebaseUid()));
    }

    // Story 6.2 — Générer le lien d'onboarding Stripe (WebView)
    @PostMapping("/connect/onboarding-link")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<OnboardingLinkResponse> createOnboardingLink() {
        return ResponseEntity.ok(paymentService.createOnboardingLink(requireFirebaseUid()));
    }

    // Re-pulls the Stripe account state and syncs stripe_onboarded. Recovery path when the
    // account.updated webhook was missed (local dev without Stripe CLI, transient outage).
    @PostMapping("/connect/refresh")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<ConnectAccountResponse> refreshConnectAccount() {
        return ResponseEntity.ok(paymentService.refreshConnectAccount(requireFirebaseUid()));
    }

    // Story 6.3 — Créer un PaymentIntent en escrow pour un bid accepté
    @PostMapping
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<PaymentResponse> createEscrow(
            @Valid @RequestBody CreatePaymentRequest request) {
        return ResponseEntity.ok(paymentService.createEscrow(request, requireFirebaseUid()));
    }

    // Statut paiement pour un bid — expéditeur ou voyageur (ownership vérifiée dans le service)
    @GetMapping("/bid/{bidId}")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<PaymentResponse> getPaymentForBid(@PathVariable UUID bidId) {
        String callerUid = requireFirebaseUid();
        return paymentService.getPaymentStatusForBid(bidId, callerUid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Webhook Stripe — endpoint public (signature validée dans StripeWebhookIngestService)
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        ingestService.ingest(payload, sigHeader, StripeWebhookSource.PAYMENTS);
        return ResponseEntity.ok().build();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
