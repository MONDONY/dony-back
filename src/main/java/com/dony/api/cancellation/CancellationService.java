package com.dony.api.cancellation;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.dto.CancellationRequest;
import com.dony.api.cancellation.dto.CancellationResponse;
import com.dony.api.cancellation.dto.RematchSuggestionDto;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.cancellation.events.TravelerHighCancellationEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CancellationService {

    private final CancellationRepository cancellationRepository;
    private final RematchSuggestionRepository rematchSuggestionRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public CancellationService(CancellationRepository cancellationRepository,
                                RematchSuggestionRepository rematchSuggestionRepository,
                                BidRepository bidRepository,
                                AnnouncementRepository announcementRepository,
                                UserRepository userRepository,
                                AuditService auditService,
                                ApplicationEventPublisher eventPublisher) {
        this.cancellationRepository = cancellationRepository;
        this.rematchSuggestionRepository = rematchSuggestionRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CancellationResponse cancelTrip(String firebaseUid, CancellationRequest request) {
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        AnnouncementEntity announcement = announcementRepository.findById(request.announcementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à annuler cette annonce");
        }

        if (announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "already-cancelled", "Already Cancelled",
                    "Ce trajet est déjà annulé");
        }

        // Cancel the announcement
        announcement.setStatus(AnnouncementStatus.CANCELLED);
        announcementRepository.save(announcement);

        // Cancel all accepted bids and create cancellation records
        List<BidEntity> acceptedBids = bidRepository.findByAnnouncementIdAndStatus(
                request.announcementId(), BidStatus.ACCEPTED);

        List<UUID> affectedSenderIds = new ArrayList<>();
        List<UUID> affectedBidIds = new ArrayList<>();
        List<CancellationEntity> cancellations = new ArrayList<>();

        for (BidEntity bid : acceptedBids) {
            bid.setStatus(BidStatus.CANCELLED);
            bidRepository.save(bid);

            CancellationEntity cancellation = new CancellationEntity();
            cancellation.setBidId(bid.getId());
            cancellation.setCancelledBy(traveler.getId());
            cancellation.setReason(request.reason());
            cancellations.add(cancellationRepository.save(cancellation));

            affectedSenderIds.add(bid.getSenderId());
            affectedBidIds.add(bid.getId());
        }

        // Track cancellation count on traveler profile for reputation penalty
        traveler.setCancellationCount(traveler.getCancellationCount() + 1);
        userRepository.save(traveler);

        int cancellationCount = traveler.getCancellationCount();

        auditService.log("ANNOUNCEMENT", request.announcementId(), "TRIP_CANCELLED", traveler.getId(),
                Map.of("reason", request.reason(),
                       "affectedBids", String.valueOf(acceptedBids.size()),
                       "cancellationCount", String.valueOf(cancellationCount)));

        if (cancellationCount >= 3) {
            auditService.log("USER", traveler.getId(), "HIGH_CANCELLATION_ALERT", traveler.getId(),
                    Map.of("cancellationCount", String.valueOf(cancellationCount),
                           "triggeringAnnouncementId", request.announcementId().toString()));
            eventPublisher.publishEvent(new TravelerHighCancellationEvent(
                    traveler.getId(), cancellationCount, request.announcementId()));
        }

        // Publish event for notifications (Epic 8) and payment refunds (Story 6.7)
        eventPublisher.publishEvent(new TripCancelledEvent(
                request.announcementId(), traveler.getId(), affectedSenderIds, request.reason(),
                affectedBidIds));

        // Generate rematch suggestions for each affected bid
        List<RematchSuggestionDto> suggestions = generateRematchSuggestions(
                announcement, acceptedBids, cancellations);

        return new CancellationResponse(
                request.announcementId(),
                acceptedBids.size(),
                request.reason(),
                suggestions,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    @Transactional(readOnly = true)
    public List<RematchSuggestionDto> getRematchSuggestions(UUID cancellationId) {
        CancellationEntity cancellation = cancellationRepository.findById(cancellationId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "cancellation-not-found", "Not Found", "Annulation introuvable"));

        return rematchSuggestionRepository.findByCancellationId(cancellationId)
                .stream().map(s -> {
                    AnnouncementEntity a = announcementRepository.findById(s.getAnnouncementId()).orElse(null);
                    if (a == null) return null;
                    return new RematchSuggestionDto(s.getId(), a.getId(),
                            a.getDepartureCity(), a.getArrivalCity(),
                            a.getDepartureDate(), a.getAvailableKg(), a.getPricePerKg());
                })
                .filter(s -> s != null)
                .toList();
    }

    private List<RematchSuggestionDto> generateRematchSuggestions(
            AnnouncementEntity cancelled,
            List<BidEntity> affectedBids,
            List<CancellationEntity> cancellations) {

        if (affectedBids.isEmpty()) return List.of();

        // Find alternatives on same corridor within 72h
        LocalDate from = cancelled.getDepartureDate();
        LocalDate to = from.plusDays(3);

        // Find active announcements on same corridor, within 72h, with capacity
        List<AnnouncementEntity> alternatives = announcementRepository.findAll().stream()
                .filter(a -> a.getStatus() == AnnouncementStatus.ACTIVE)
                .filter(a -> !a.getId().equals(cancelled.getId()))
                .filter(a -> a.getDepartureCity().equalsIgnoreCase(cancelled.getDepartureCity()))
                .filter(a -> a.getArrivalCity().equalsIgnoreCase(cancelled.getArrivalCity()))
                .filter(a -> !a.getDepartureDate().isBefore(from) && !a.getDepartureDate().isAfter(to))
                .limit(5)
                .toList();

        List<RematchSuggestionDto> result = new ArrayList<>();

        // Create rematch suggestion records for the first affected bid's cancellation
        if (!cancellations.isEmpty() && !alternatives.isEmpty()) {
            CancellationEntity firstCancellation = cancellations.get(0);
            for (AnnouncementEntity alt : alternatives) {
                RematchSuggestionEntity suggestion = new RematchSuggestionEntity();
                suggestion.setCancellationId(firstCancellation.getId());
                suggestion.setAnnouncementId(alt.getId());
                RematchSuggestionEntity saved = rematchSuggestionRepository.save(suggestion);

                result.add(new RematchSuggestionDto(saved.getId(), alt.getId(),
                        alt.getDepartureCity(), alt.getArrivalCity(),
                        alt.getDepartureDate(), alt.getAvailableKg(), alt.getPricePerKg()));
            }
        }

        return result;
    }

    private UserEntity findUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
    }
}
