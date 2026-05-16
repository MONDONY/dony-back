package com.dony.api.matching;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidResponse;
import com.dony.api.matching.dto.CalendarStatsResponse;
import com.dony.api.matching.dto.InviteRequest;
import com.dony.api.matching.dto.MatchingRequestDto;
import com.dony.api.matching.dto.ProAnalyticsResponse;
import com.dony.api.matching.dto.TravelerStatsDto;
import com.dony.api.notifications.NotificationDispatcher;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.repository.PackageRequestRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/travelers")
public class TravelerStatsController {

    private final TravelerStatsService statsService;
    private final UserRepository userRepository;
    private final ProAnalyticsService analyticsService;
    private final AnnouncementRepository announcementRepository;
    private final MatchingService matchingService;
    private final PackageRequestRepository packageRequestRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final BidService bidService;

    public TravelerStatsController(
            TravelerStatsService statsService,
            UserRepository userRepository,
            ProAnalyticsService analyticsService,
            AnnouncementRepository announcementRepository,
            MatchingService matchingService,
            PackageRequestRepository packageRequestRepository,
            NotificationDispatcher notificationDispatcher,
            BidService bidService) {
        this.statsService = statsService;
        this.userRepository = userRepository;
        this.analyticsService = analyticsService;
        this.announcementRepository = announcementRepository;
        this.matchingService = matchingService;
        this.packageRequestRepository = packageRequestRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.bidService = bidService;
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

    @GetMapping("/me/matching-requests")
    public ResponseEntity<List<MatchingRequestDto>> getMatchingRequests() {
        String firebaseUid = requireFirebaseUid();
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Demandes compatibles réservées aux voyageurs PRO.");
        }
        return ResponseEntity.ok(matchingService.findMatchingRequests(user.getId()));
    }

    @PostMapping("/me/invite")
    public ResponseEntity<Void> inviteSender(@Valid @RequestBody InviteRequest body) {
        String firebaseUid = requireFirebaseUid();
        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!traveler.getRoles().contains(Role.TRAVELER) || !traveler.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Invitations réservées aux voyageurs PRO.");
        }

        AnnouncementEntity announcement = announcementRepository.findById(body.announcementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found",
                        "Announcement Not Found", "Annonce introuvable."));
        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "not-your-announcement",
                    "Forbidden", "Cette annonce ne vous appartient pas.");
        }

        PackageRequestEntity request = packageRequestRepository.findById(body.requestId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "request-not-found",
                        "Request Not Found", "Demande introuvable."));
        if (request.getStatus() != PackageRequestStatus.OPEN) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "request-not-open",
                    "Request Not Open", "Cette demande n'est plus disponible.");
        }

        String travelerName = buildTravelerName(traveler);
        String corridor = announcement.getDepartureCity() + " → " + announcement.getArrivalCity();
        notificationDispatcher.notifyUser(
                request.getSenderId(),
                "Invitation d'un voyageur",
                travelerName + " vous invite à envoyer votre colis sur le trajet " + corridor + ".",
                Map.of(
                        "type", "TRAVELER_INVITE",
                        "announcementId", body.announcementId().toString(),
                        "requestId", body.requestId().toString()
                )
        );

        return ResponseEntity.ok().build();
    }

    private String buildTravelerName(UserEntity user) {
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "Un voyageur" : name;
    }

    @GetMapping("/me/bids")
    public ResponseEntity<Page<BidResponse>> getMyBids(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getTravelerBids(firebaseUid, status, tripId, q, page, size));
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
