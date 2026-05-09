package com.dony.api.ratings;

import com.dony.api.ratings.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    // Story 9.1 — Expéditeur authentifié note le voyageur
    @PostMapping
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<RatingResponse> createRating(Principal principal,
                                                       @Valid @RequestBody RatingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ratingService.createRating(principal.getName(), request));
    }

    // Story 9.2 — Destinataire sans compte note le voyageur (endpoint public)
    @PostMapping("/recipient")
    public ResponseEntity<RatingResponse> createRecipientRating(@Valid @RequestBody RecipientRatingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ratingService.createRecipientRating(request));
    }

    // Story 9.3 — Voyageur note l'expéditeur
    @PostMapping("/traveler-to-sender")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<RatingResponse> createTravelerRating(Principal principal,
                                                               @Valid @RequestBody TravelerRatingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ratingService.createTravelerRating(principal.getName(), request));
    }

    // Profil public — liste paginée des notes reçues par un utilisateur
    @GetMapping("/user/{userId}")
    public ResponseEntity<UserRatingsSummaryResponse> getUserRatings(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ratingService.getUserRatings(userId, page, size));
    }

    // Notation en attente au démarrage de l'app
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<PendingRatingResponse> getPendingRating(Principal principal) {
        return ratingService.getPendingRating(principal.getName())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}
