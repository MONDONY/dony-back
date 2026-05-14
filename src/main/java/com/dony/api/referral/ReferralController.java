package com.dony.api.referral;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the referral system.
 *
 * <ul>
 *   <li>{@code GET  /me/referral}        — get code + stats</li>
 *   <li>{@code POST /me/referral/regenerate} — regenerate code (cooldown applies)</li>
 *   <li>{@code POST /referral/redeem}    — redeem a code on sign-up</li>
 * </ul>
 */
@RestController
public class ReferralController {

    private final ReferralService referralService;

    public ReferralController(ReferralService referralService) {
        this.referralService = referralService;
    }

    /**
     * Returns the authenticated user's referral code and invitation statistics.
     * Lazily creates a code if not yet generated.
     */
    @GetMapping("/me/referral")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<MyReferralResponse> getMyReferral(Authentication auth) {
        String firebaseUid = (String) auth.getPrincipal();
        return ResponseEntity.ok(referralService.getMyReferral(firebaseUid));
    }

    /**
     * Regenerates the authenticated user's referral code.
     * Subject to a cooldown configured in {@code dony.referral.code-regeneration-cooldown-days}.
     */
    @PostMapping("/me/referral/regenerate")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<MyReferralResponse> regenerateCode(Authentication auth) {
        String firebaseUid = (String) auth.getPrincipal();
        return ResponseEntity.ok(referralService.regenerateCode(firebaseUid));
    }

    /**
     * Redeems a referral code on behalf of the authenticated user (referee).
     * Returns 204 No Content on success.
     */
    @PostMapping("/referral/redeem")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<Void> redeemCode(Authentication auth,
                                           @Valid @RequestBody RedeemCodeRequest request) {
        String firebaseUid = (String) auth.getPrincipal();
        referralService.redeemCode(firebaseUid, request.code());
        return ResponseEntity.noContent().build();
    }
}
