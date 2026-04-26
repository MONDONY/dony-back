package com.dony.api.tracking;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.StorageService;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.tracking.dto.ConfirmDeliveryRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Controller
@RequestMapping("/tracking/public")
public class RecipientController {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRENCH);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final StorageService storageService;
    private final TrackingService trackingService;

    public RecipientController(BidRepository bidRepository,
                               AnnouncementRepository announcementRepository,
                               TrackingEventRepository trackingEventRepository,
                               StorageService storageService,
                               TrackingService trackingService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.storageService = storageService;
        this.trackingService = trackingService;
    }

    @GetMapping("/{trackingToken}")
    public String trackingPage(@PathVariable String trackingToken, Model model) {
        return buildTrackingPage(trackingToken, model, null, null, false);
    }

    @PostMapping("/{trackingToken}")
    public String confirmPage(@PathVariable String trackingToken,
                              @RequestParam("confirmationCode") String code,
                              Model model) {
        Optional<BidEntity> bidOpt = bidRepository.findByTrackingToken(trackingToken);
        if (bidOpt.isEmpty()) {
            model.addAttribute("invalid", true);
            return "recipient/tracking";
        }
        BidEntity bid = bidOpt.get();
        try {
            trackingService.confirmDelivery(bid.getId(), new ConfirmDeliveryRequest(code.trim()));
            return buildTrackingPage(trackingToken, model, null, null, true);
        } catch (DonyBusinessException ex) {
            return buildTrackingPage(trackingToken, model, ex.getLocalizedMessage(), code, false);
        }
    }

    private String buildTrackingPage(String trackingToken, Model model,
                                     String errorMessage, String previousCode,
                                     boolean justConfirmed) {
        Optional<BidEntity> bidOpt = bidRepository.findByTrackingToken(trackingToken);

        if (bidOpt.isEmpty()) {
            model.addAttribute("invalid", true);
            return "recipient/tracking";
        }

        BidEntity bid = bidOpt.get();

        Optional<AnnouncementEntity> announcementOpt =
                announcementRepository.findById(bid.getAnnouncementId());

        String corridor = announcementOpt.map(a ->
                a.getDepartureCity() + " → " + a.getArrivalCity()
        ).orElse("—");

        List<TrackingEventEntity> rawEvents =
                trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId());

        List<RecipientEventDto> events = rawEvents.stream()
                .map(e -> new RecipientEventDto(
                        labelFor(e.getEventType()),
                        e.getScannedAt().format(FMT),
                        resolvePhoto(e.getPhotoUrl()),
                        e.getOfflineTimestamp() != null
                ))
                .toList();

        String currentStepLabel = resolveCurrentStep(bid, rawEvents);

        boolean hasArrivee = rawEvents.stream()
                .anyMatch(e -> e.getEventType() == TrackingEventType.ARRIVEE);
        boolean canConfirm = bid.getStatus() == BidStatus.ACCEPTED && !hasArrivee;

        model.addAttribute("trackingNumber", bid.getTrackingNumber());
        model.addAttribute("corridor", corridor);
        model.addAttribute("recipientName", bid.getRecipientName());
        model.addAttribute("currentStep", currentStepLabel);
        model.addAttribute("events", events);
        model.addAttribute("invalid", false);
        model.addAttribute("canConfirm", canConfirm);
        model.addAttribute("trackingToken", trackingToken);
        model.addAttribute("errorMessage", errorMessage);
        model.addAttribute("previousCode", previousCode != null ? previousCode : "");
        model.addAttribute("justConfirmed", justConfirmed);

        return "recipient/tracking";
    }

    private String labelFor(TrackingEventType type) {
        return switch (type) {
            case DEPART -> "Départ confirmé";
            case TRANSIT -> "En transit";
            case ARRIVEE -> "Arrivée confirmée";
        };
    }

    private String resolvePhoto(String key) {
        if (key == null || key.startsWith("http")) return key;
        return storageService.generatePresignedUrl(key, Duration.ofHours(1));
    }

    private String resolveCurrentStep(BidEntity bid, List<TrackingEventEntity> events) {
        boolean hasArrivee = events.stream()
                .anyMatch(e -> e.getEventType() == TrackingEventType.ARRIVEE);
        boolean hasTransit = events.stream()
                .anyMatch(e -> e.getEventType() == TrackingEventType.TRANSIT);
        boolean hasDepart = events.stream()
                .anyMatch(e -> e.getEventType() == TrackingEventType.DEPART);

        if (hasArrivee) return "Livraison confirmée ✓";
        if (hasTransit) return "En transit";
        if (hasDepart) return "Départ confirmé — en route";
        return "En attente du scan de départ";
    }

    public record RecipientEventDto(
            String label,
            String scannedAt,
            String photoUrl,
            boolean offline
    ) {}
}
