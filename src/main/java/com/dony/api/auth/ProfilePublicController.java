package com.dony.api.auth;

import com.dony.api.auth.dto.ProfilePublicResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/users")
public class ProfilePublicController {

    private final ProfilePublicService profilePublicService;

    public ProfilePublicController(ProfilePublicService profilePublicService) {
        this.profilePublicService = profilePublicService;
    }

    @GetMapping("/{userId}/profile-public")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<ProfilePublicResponse> getProfilePublic(@PathVariable UUID userId) {
        return ResponseEntity.ok(profilePublicService.getProfilePublic(userId));
    }
}
