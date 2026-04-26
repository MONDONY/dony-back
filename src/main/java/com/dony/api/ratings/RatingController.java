package com.dony.api.ratings;

import com.dony.api.auth.FirebaseTokenFilter;
import com.dony.api.ratings.dto.RatingRequest;
import com.dony.api.ratings.dto.RatingResponse;
import com.dony.api.ratings.dto.RecipientRatingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

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
        RatingResponse response = ratingService.createRating(principal.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Story 9.2 — Destinataire sans compte note le voyageur (endpoint public)
    @PostMapping("/recipient")
    public ResponseEntity<RatingResponse> createRecipientRating(@Valid @RequestBody RecipientRatingRequest request) {
        RatingResponse response = ratingService.createRecipientRating(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
