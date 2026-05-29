package com.dony.api.auth;

import com.dony.api.auth.dto.PublicTravelerProfileResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public (no-auth) shareable traveler profile.
 * Registered under {@code /public/**} which is permitAll in SecurityConfig.
 */
@RestController
@RequestMapping("/public/travelers")
public class PublicTravelerController {

    private final ProfilePublicService profilePublicService;

    public PublicTravelerController(ProfilePublicService profilePublicService) {
        this.profilePublicService = profilePublicService;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<PublicTravelerProfileResponse> getPublicProfile(@PathVariable UUID userId) {
        return ResponseEntity.ok(profilePublicService.getPublicTravelerProfile(userId));
    }
}
