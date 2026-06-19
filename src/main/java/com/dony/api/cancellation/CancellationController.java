package com.dony.api.cancellation;

import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.dto.CancellationRequest;
import com.dony.api.cancellation.dto.CancellationResponse;
import com.dony.api.cancellation.dto.RematchSuggestionDto;
import com.dony.api.common.DonyBusinessException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/cancellations")
public class CancellationController {

    private final CancellationService cancellationService;
    private final UserRepository userRepository;

    public CancellationController(CancellationService cancellationService,
                                   UserRepository userRepository) {
        this.cancellationService = cancellationService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<CancellationResponse> cancelTrip(
            @Valid @RequestBody CancellationRequest request
    ) {
        String firebaseUid = requireFirebaseUid();
        CancellationResponse response = cancellationService.cancelTrip(firebaseUid, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{cancellationId}/rematch-suggestions")
    public ResponseEntity<List<RematchSuggestionDto>> getRematchSuggestions(
            @PathVariable UUID cancellationId
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(cancellationService.getRematchSuggestions(cancellationId, firebaseUid));
    }

    @PostMapping("/bids/{bidId}/report-noshow")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<Void> reportNoShow(@PathVariable UUID bidId) {
        UUID travelerId = resolveUserId();
        cancellationService.reportSenderNoShow(bidId, travelerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/confirm-noshow")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> confirmNoShow(@PathVariable UUID bidId) {
        cancellationService.confirmSenderNoShow(bidId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/confirm-noshow-self")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> confirmNoShowSelf(@PathVariable UUID bidId) {
        UUID senderId = resolveUserId();
        cancellationService.confirmSenderNoShow(bidId, senderId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/contest-noshow")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> contestNoShow(@PathVariable UUID bidId) {
        UUID senderId = resolveUserId();
        cancellationService.contestSenderNoShow(bidId, senderId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/report-traveler-noshow")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> reportTravelerNoShow(@PathVariable UUID bidId) {
        UUID senderId = resolveUserId();
        cancellationService.reportTravelerNoShow(bidId, senderId);
        return ResponseEntity.ok().build();
    }

    // Le voyageur confirme le retour du colis (annulation après remise) en saisissant
    // le code de retour détenu par l'expéditeur.
    @PostMapping("/bids/{bidId}/confirm-return")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<com.dony.api.cancellation.dto.ReturnCodeResponse> confirmReturn(
            @PathVariable UUID bidId,
            @Valid @RequestBody com.dony.api.cancellation.dto.ConfirmReturnRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(
                cancellationService.confirmReturn(firebaseUid, bidId, request.returnCode()));
    }

    // L'expéditeur consulte son code de retour + l'état du retour.
    @GetMapping("/bids/{bidId}/return-code")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<com.dony.api.cancellation.dto.ReturnCodeResponse> getReturnCode(
            @PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(cancellationService.getReturnCode(firebaseUid, bidId));
    }

    private UUID resolveUserId() {
        String firebaseUid = requireFirebaseUid();
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(u -> u.getId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));
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
