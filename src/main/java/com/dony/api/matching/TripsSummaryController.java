package com.dony.api.matching;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.TripsSummaryDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/travelers")
public class TripsSummaryController {

    private final TripsSummaryService tripsSummaryService;
    private final UserRepository userRepository;

    public TripsSummaryController(
            TripsSummaryService tripsSummaryService,
            UserRepository userRepository) {
        this.tripsSummaryService = tripsSummaryService;
        this.userRepository = userRepository;
    }

    /**
     * Résumé d'activité voyageur (statistiques du hub Activités).
     * Contrairement à /me/stats, accessible à tout voyageur (pas de gate Pro).
     *
     * @param period {@code 7d}, {@code 30d} (défaut) ou {@code 12m}. Une valeur
     *               inconnue retombe silencieusement sur le défaut plutôt que
     *               de renvoyer une erreur.
     */
    @GetMapping("/me/trips-summary")
    public ResponseEntity<TripsSummaryDto> getMyTripsSummary(
            @RequestParam(required = false, defaultValue = TripsSummaryService.DEFAULT_PERIOD)
            String period) {
        String firebaseUid = requireFirebaseUid();

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));

        if (!user.getRoles().contains(Role.TRAVELER)) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "traveler-required",
                    "Traveler role required",
                    "Réservé aux voyageurs.");
        }

        return ResponseEntity.ok(tripsSummaryService.computeSummary(user, period));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Unauthenticated", "Authentification requise");
        }
        return auth.getName();
    }
}
