package com.dony.api.admin;

import com.dony.api.auth.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    // Story 9.5 — Admin lifts a suspension manually
    @PostMapping("/{userId}/unsuspend")
    public ResponseEntity<Void> unsuspendUser(@PathVariable UUID userId) {
        userService.unsuspendUser(userId);
        return ResponseEntity.noContent().build();
    }

    // D4 — Admin suspend la publication de trajets (retour de colis non rendu).
    @PostMapping("/{userId}/suspend-publishing")
    public ResponseEntity<Void> suspendPublishing(@PathVariable UUID userId,
            @RequestParam(required = false) String reason) {
        userService.suspendPublishing(userId, reason);
        return ResponseEntity.noContent().build();
    }

    // D4 — Admin lève la suspension de publication.
    @PostMapping("/{userId}/lift-publishing-suspension")
    public ResponseEntity<Void> liftPublishingSuspension(@PathVariable UUID userId) {
        userService.liftPublishingSuspension(userId);
        return ResponseEntity.noContent().build();
    }

    // Override du taux de commission Dony pour un utilisateur (null = taux global).
    @PutMapping("/{userId}/commission-rate")
    public ResponseEntity<Void> setCommissionRate(
            @PathVariable UUID userId,
            @RequestBody @jakarta.validation.Valid CommissionRateOverrideRequest request) {
        userService.setCommissionRateOverride(userId, request.rate());
        return ResponseEntity.noContent().build();
    }
}
