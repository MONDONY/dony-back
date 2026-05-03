package com.dony.api.cancellation;

import com.dony.api.cancellation.dto.CancellationRequest;
import com.dony.api.cancellation.dto.CancellationResponse;
import com.dony.api.cancellation.dto.RematchSuggestionDto;
import com.dony.api.common.DonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public CancellationController(CancellationService cancellationService) {
        this.cancellationService = cancellationService;
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

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
