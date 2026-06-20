package com.dony.api.alerts;

import com.dony.api.alerts.dto.CorridorAlertRequest;
import com.dony.api.alerts.dto.CorridorAlertResponse;
import com.dony.api.matching.dto.MatchingRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/me/corridor-alerts")
    @PreAuthorize("hasRole('TRAVELER')")
    public List<CorridorAlertResponse> list(@AuthenticationPrincipal String firebaseUid) {
        return alertService.list(firebaseUid);
    }

    @PostMapping("/me/corridor-alerts")
    @PreAuthorize("hasRole('TRAVELER')")
    @ResponseStatus(HttpStatus.CREATED)
    public CorridorAlertResponse create(@AuthenticationPrincipal String firebaseUid,
                                        @Valid @RequestBody CorridorAlertRequest request) {
        return alertService.create(firebaseUid, request);
    }

    @PutMapping("/me/corridor-alerts/{id}")
    @PreAuthorize("hasRole('TRAVELER')")
    public CorridorAlertResponse update(@AuthenticationPrincipal String firebaseUid,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody CorridorAlertRequest request) {
        return alertService.update(firebaseUid, id, request, request.active());
    }

    @DeleteMapping("/me/corridor-alerts/{id}")
    @PreAuthorize("hasRole('TRAVELER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal String firebaseUid,
                       @PathVariable UUID id) {
        alertService.delete(firebaseUid, id);
    }

    @GetMapping("/me/corridor-alerts/{id}/matches")
    @PreAuthorize("hasRole('TRAVELER')")
    public List<MatchingRequestDto> matches(@AuthenticationPrincipal String firebaseUid,
                                            @PathVariable UUID id) {
        return alertService.getMatches(firebaseUid, id);
    }
}
