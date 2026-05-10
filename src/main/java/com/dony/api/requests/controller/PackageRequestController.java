package com.dony.api.requests.controller;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.requests.dto.*;
import com.dony.api.requests.entity.ParcelSize;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.service.PackageRequestService;
import com.dony.api.requests.service.PriceEstimationService;
import com.dony.api.requests.specification.PackageRequestSpecifications;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/package-requests")
public class PackageRequestController {

    private final PackageRequestService service;
    private final PriceEstimationService estimationService;
    private final com.dony.api.requests.service.NegotiationService negotiationService;
    private final UserRepository userRepository;

    public PackageRequestController(PackageRequestService service,
                                    PriceEstimationService estimationService,
                                    com.dony.api.requests.service.NegotiationService negotiationService,
                                    UserRepository userRepository) {
        this.service = service;
        this.estimationService = estimationService;
        this.negotiationService = negotiationService;
        this.userRepository = userRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<PackageRequestResponse> create(@RequestBody @Valid PackageRequestCreateRequest req) {
        UUID userId = requireUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, req));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('SENDER')")
    public Page<PackageRequestResponse> findMine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.findMine(requireUserId(), PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public PackageRequestResponse getById(@PathVariable UUID id) {
        return service.getById(requireUserId(), id);
    }

    @GetMapping("/{id}/threads")
    @PreAuthorize("hasRole('SENDER')")
    public java.util.List<com.dony.api.requests.dto.NegotiationThreadResponse> listThreads(@PathVariable UUID id) {
        return negotiationService.listForRequest(requireUserId(), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        service.cancel(requireUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete-details")
    @PreAuthorize("hasRole('SENDER')")
    public PackageRequestResponse completeDetails(
            @PathVariable UUID id,
            @RequestBody @Valid PackageRequestCompleteDetailsRequest req,
            HttpServletRequest httpRequest
    ) {
        String clientIp = extractClientIp(httpRequest);
        return service.completeDetails(requireUserId(), id, req, clientIp);
    }

    @GetMapping
    @PreAuthorize("hasRole('TRAVELER')")
    public Page<PackageRequestSearchResponse> search(
            @RequestParam(required = false) String departure,
            @RequestParam(required = false) String arrival,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) BigDecimal maxWeight,
            @RequestParam(required = false) ParcelSize parcelSize,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Specification<PackageRequestEntity> spec = Specification
                .where(PackageRequestSpecifications.openOnly())
                .and(PackageRequestSpecifications.corridor(departure, arrival))
                .and(PackageRequestSpecifications.dateRange(dateFrom, dateTo))
                .and(PackageRequestSpecifications.maxWeight(maxWeight))
                .and(PackageRequestSpecifications.parcelSize(parcelSize));
        return service.search(spec, PageRequest.of(page, size));
    }

    @GetMapping("/estimate")
    public PriceEstimateResponse estimate(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam BigDecimal weight
    ) {
        return estimationService.estimate(from, to, weight);
    }

    // ─── Auth helpers ────────────────────────────────────────────────────────────

    private UUID requireUserId() {
        String firebaseUid = requireFirebaseUid();
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"))
                .getId();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Un token Firebase valide est requis"
            );
        }
        return (String) auth.getPrincipal();
    }

    /**
     * Project rule: use the LAST X-Forwarded-For element (added by trusted proxy),
     * not the first — clients can spoof the first element.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
