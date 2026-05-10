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
    private final UserRepository userRepository;

    public NegotiationController(NegotiationService service, UserRepository userRepository) {
        this.service = service;
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
    @PreAuthorize("hasRole('SENDER')")
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
        return service.submitTrip(requireUserId(), id, req.travelerAnnouncementId());
    }

    /** Sender confirms payment for an AWAITING_PAYMENT thread → finalize as ACCEPTED. */
    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasRole('SENDER')")
    public NegotiationThreadResponse checkout(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationCheckoutRequest req
    ) {
        return service.finalizeAfterPayment(requireUserId(), id, req.paymentIntentId());
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
