package com.dony.api.matching;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.CalendarStatsResponse;
import com.dony.api.matching.dto.ProAnalyticsResponse;
import com.dony.api.matching.dto.TravelerStatsDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/travelers")
public class TravelerStatsController {

    private final TravelerStatsService statsService;
    private final UserRepository userRepository;
    private final ProAnalyticsService analyticsService;
    private final AnnouncementRepository announcementRepository;

    public TravelerStatsController(
            TravelerStatsService statsService,
            UserRepository userRepository,
            ProAnalyticsService analyticsService,
            AnnouncementRepository announcementRepository) {
        this.statsService = statsService;
        this.userRepository = userRepository;
        this.analyticsService = analyticsService;
        this.announcementRepository = announcementRepository;
    }

    @GetMapping("/me/stats")
    public ResponseEntity<TravelerStatsDto> getMyStats() {
        String firebaseUid = requireFirebaseUid();

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Les statistiques sont réservées aux voyageurs PRO.");
        }

        return ResponseEntity.ok(statsService.computeStats(user));
    }

    @GetMapping("/me/analytics")
    public ResponseEntity<ProAnalyticsResponse> getMyAnalytics(
            @RequestParam(defaultValue = "month") String period) {
        String firebaseUid = requireFirebaseUid();
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Analytics réservés aux voyageurs PRO.");
        }
        if (!List.of("month", "quarter", "year").contains(period)) {
            throw new DonyBusinessException(
                    HttpStatus.BAD_REQUEST, "invalid-period",
                    "Invalid period", "Période invalide. Valeurs acceptées: month, quarter, year.");
        }
        return ResponseEntity.ok(analyticsService.computeAnalytics(user, period));
    }

    @GetMapping("/me/calendar")
    public ResponseEntity<CalendarStatsResponse> getMyCalendar() {
        String firebaseUid = requireFirebaseUid();
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Calendrier réservé aux voyageurs PRO.");
        }
        YearMonth current = YearMonth.now();
        LocalDateTime from = current.atDay(1).atStartOfDay();
        LocalDateTime to = current.atEndOfMonth().atTime(23, 59, 59);
        long activeTrips = announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.ACTIVE);
        long totalMonth = announcementRepository.countByTravelerIdAndCreatedAtBetween(user.getId(), from, to);
        return ResponseEntity.ok(new CalendarStatsResponse(activeTrips, totalMonth));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise");
        }
        return auth.getName();
    }
}
