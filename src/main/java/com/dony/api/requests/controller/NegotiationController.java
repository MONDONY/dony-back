package com.dony.api.requests.controller;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.requests.dto.*;
import com.dony.api.requests.service.NegotiationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/negotiations")
public class NegotiationController {

    private final NegotiationService service;
    private final com.dony.api.payments.PaymentService paymentService;
    private final UserRepository userRepository;

    public NegotiationController(NegotiationService service,
                                 com.dony.api.payments.PaymentService paymentService,
                                 UserRepository userRepository) {
        this.service = service;
        this.paymentService = paymentService;
        this.userRepository = userRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<NegotiationThreadResponse> start(@RequestBody @Valid NegotiationStartRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.start(requireUserId(), req));
    }

    @GetMapping("/me")
    public List<NegotiationThreadResponse> findMine() {
        return service.listMine(requireUserId());
    }

    @GetMapping("/{id}")
    public NegotiationThreadResponse getById(@PathVariable UUID id) {
        return service.getById(requireUserId(), id);
    }

    @PostMapping("/{id}/counter")
    public NegotiationThreadResponse counter(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationCounterRequest req
    ) {
        return service.counter(requireUserId(), id, req);
    }

    @PostMapping("/{id}/accept")
    public NegotiationThreadResponse accept(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationAcceptRequest req
    ) {
        return service.accept(requireUserId(), id, req);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationRejectRequest req
    ) {
        service.reject(requireUserId(), id, req);
        return ResponseEntity.noContent().build();
    }

    /** Traveler links a trip (existing announcement) to an AWAITING_TRIP thread. */
    @PostMapping("/{id}/submit-trip")
    @PreAuthorize("hasRole('TRAVELER')")
    public NegotiationThreadResponse submitTrip(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationSubmitTripRequest req
    ) {
        return service.submitTrip(requireUserId(), id, req);
    }

    /**
     * Traveler creates a brand-new "dedicated trip" announcement to satisfy
     * an AWAITING_TRIP thread (used when no existing trip matches). The trip
     * is private (excluded from public search) and the thread transitions to
     * AWAITING_PAYMENT atomically.
     */
    @PostMapping("/{id}/create-dedicated-trip")
    @PreAuthorize("hasRole('TRAVELER')")
    public NegotiationThreadResponse createDedicatedTrip(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationCreateDedicatedTripRequest req
    ) {
        return service.createDedicatedTrip(requireUserId(), id, req);
    }

    /** Sender confirms payment for an AWAITING_PAYMENT thread → finalize as ACCEPTED. */
    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasRole('SENDER')")
    public NegotiationThreadResponse checkout(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationCheckoutRequest req
    ) {
        return service.checkout(requireUserId(), id, req.paymentIntentId(), req.paymentMethod());
    }

    /**
     * Sender refuses the linked trip — thread moves back to AWAITING_TRIP.
     * Only the sender of the package_request can call this endpoint.
     */
    @PostMapping("/{id}/refuse-trip")
    @PreAuthorize("hasRole('SENDER')")
    public NegotiationThreadResponse refuseTrip(
            @PathVariable UUID id,
            @RequestBody(required = false) @Valid NegotiationRefuseTripRequest req) {
        return service.refuseTrip(requireUserId(), id, req != null ? req.reason() : null);
    }

    /**
     * Sender initiates the Stripe escrow payment for an AWAITING_PAYMENT thread.
     * Returns the Stripe clientSecret to confirm via the Flutter SDK.
     * The thread is finalized to ACCEPTED only when the webhook fires
     * payment_intent.amount_capturable_updated (escrow active).
     */
    @PostMapping("/{id}/initiate-payment")
    @PreAuthorize("hasRole('SENDER')")
    public com.dony.api.payments.dto.PaymentResponse initiatePayment(@PathVariable UUID id) {
        UUID senderId = requireUserId();
        var thread = service.getById(senderId, id);
        // Defensive: only allowed if thread status = AWAITING_PAYMENT
        if (thread.status() != com.dony.api.requests.entity.NegotiationThreadStatus.AWAITING_PAYMENT) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "thread/not-awaiting-payment");
        }
        return paymentService.createNegotiationEscrow(
            id, senderId, thread.travelerId(), thread.currentPriceEur());
    }

    /**
     * Traveler opens the remaining (surplus) capacity of a DEDICATED trip to the
     * public, once the negotiating sender has paid (thread ACCEPTED). The path
     * param is the dedicated announcement id, not a thread id.
     */
    @PostMapping("/trip/{announcementId}/open-surplus")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<Void> openSurplus(
            @PathVariable UUID announcementId,
            @RequestBody @Valid OpenSurplusRequest req
    ) {
        service.openSurplus(requireUserId(), announcementId, req.surplusKg(), req.pricePerKg());
        return ResponseEntity.noContent().build();
    }

    // ─── Auth helper ─────────────────────────────────────────────────────────────

    private UUID requireUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Un token Firebase valide est requis"
            );
        }
        String firebaseUid = (String) auth.getPrincipal();
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"))
                .getId();
    }
}
