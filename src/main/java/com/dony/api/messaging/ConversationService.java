package com.dony.api.messaging;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.messaging.dto.ConversationResponse;
import com.dony.api.messaging.dto.ParticipantDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;

    public ConversationService(ConversationRepository conversationRepository,
                                FirestoreService firestoreService,
                                UserRepository userRepository,
                                AuditService auditService,
                                BidRepository bidRepository,
                                AnnouncementRepository announcementRepository) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
    }

    @Transactional
    public ConversationEntity getOrCreateByBidId(UUID bidId, UUID requestingUserId) {
        // Block re-creation if the conversation was previously (soft-)deleted
        if (conversationRepository.existsDeletedConversationByBidId(bidId)) {
            throw new ResponseStatusException(HttpStatus.GONE,
                "Cette conversation a été supprimée et ne peut pas être recréée.");
        }

        return conversationRepository.findByBidIdAndParticipant(bidId, requestingUserId)
            .orElseGet(() -> {
                BidEntity bid = bidRepository.findById(bidId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bid not found"));
                AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Announcement not found"));

                UUID senderId = bid.getSenderId();
                UUID travelerId = announcement.getTravelerId();

                if (!requestingUserId.equals(senderId) && !requestingUserId.equals(travelerId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied");
                }

                return createConversationForBid(bidId, senderId, travelerId);
            });
    }

    @Transactional
    public ConversationEntity createConversationForBid(UUID bidId, UUID senderId, UUID travelerId) {
        return conversationRepository.findByBidId(bidId).orElseGet(() -> {
            String firestoreId = "conv_" + bidId;

            UserEntity sender   = userRepository.findById(senderId).orElseThrow();
            UserEntity traveler = userRepository.findById(travelerId).orElseThrow();

            // Save DB entity first — so the conversation always exists even if Firestore init fails
            ConversationEntity entity = new ConversationEntity(bidId, senderId, travelerId, firestoreId);
            ConversationEntity saved = conversationRepository.save(entity);

            auditService.log("conversation", saved.getId(), "CONVERSATION_CREATED", senderId,
                Map.of("bidId", bidId.toString(), "firestoreId", firestoreId));

            // Firestore is best-effort — failure must not roll back the DB transaction
            try {
                String now = Instant.now().toString();
                Map<String, Object> data = Map.of(
                    "bidId",               bidId.toString(),
                    "senderId",            sender.getFirebaseUid(),
                    "travelerId",          traveler.getFirebaseUid(),
                    "senderName",          fullName(sender),
                    "travelerName",        fullName(traveler),
                    "createdAt",           now,
                    "lastMessageAt",       now,
                    "lastMessagePreview",  "Connexion établie !"
                );
                firestoreService.createConversation(firestoreId, data);
                firestoreService.addSystemMessage(firestoreId,
                    "Connexion établie ! Vous pouvez maintenant échanger pour organiser la remise.");
            } catch (Exception e) {
                log.warn("Firestore conversation init failed for bid {}: {}", bidId, e.getMessage());
            }

            return saved;
        });
    }

    @Transactional
    public void deleteConversation(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipant(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));

        conv.softDelete();
        conversationRepository.save(conv);

        auditService.log("conversation", conversationId, "CONVERSATION_DELETED", requestingUserId,
            Map.of("firestoreId", conv.getFirestoreConversationId()));

        // Best-effort: mark deleted in Firestore so both clients detect it via real-time stream
        try {
            firestoreService.markConversationDeleted(conv.getFirestoreConversationId());
        } catch (Exception e) {
            log.warn("Firestore markConversationDeleted failed for {}: {}", conversationId, e.getMessage());
        }
    }

    public void updateLastMessage(String firestoreConversationId, String preview) {
        firestoreService.updateLastMessage(firestoreConversationId, preview, Instant.now().toString());
    }

    public ConversationResponse toResponse(ConversationEntity conv, UUID currentUserId) {
        UUID otherUserId = conv.getSenderId().equals(currentUserId)
            ? conv.getTravelerId()
            : conv.getSenderId();

        UserEntity other = userRepository.findById(otherUserId).orElse(null);
        ParticipantDTO otherParticipant = buildParticipant(otherUserId, other);

        // Fetch trip fields from bid + announcement
        String tripOrigin = null;
        String tripDestination = null;
        String tripDate = null;
        Double tripWeightKg = null;
        String bidStatus = null;

        Optional<BidEntity> bidOpt = bidRepository.findById(conv.getBidId());
        if (bidOpt.isPresent()) {
            BidEntity bid = bidOpt.get();
            tripWeightKg = bid.getWeightKg() != null ? bid.getWeightKg().doubleValue() : null;
            bidStatus = mapBidStatus(bid.getStatus());

            Optional<AnnouncementEntity> annOpt = announcementRepository.findById(bid.getAnnouncementId());
            if (annOpt.isPresent()) {
                AnnouncementEntity ann = annOpt.get();
                tripOrigin = ann.getDepartureCity();
                tripDestination = ann.getArrivalCity();
                tripDate = ann.getDepartureDate() != null ? ann.getDepartureDate().toString() : null;
            }
        }

        return new ConversationResponse(
            conv.getId(),
            conv.getBidId(),
            conv.getFirestoreConversationId(),
            otherParticipant,
            null,    // lastMessagePreview — lives in Firestore, not SQL
            conv.getUpdatedAt(),
            false,   // hasUnread — determined client-side via Firestore
            tripOrigin,
            tripDestination,
            tripDate,
            tripWeightKg,
            bidStatus
        );
    }

    private ParticipantDTO buildParticipant(UUID userId, UserEntity user) {
        if (user == null) {
            return new ParticipantDTO(userId.toString(), "Utilisateur inconnu", null);
        }
        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
            + (user.getLastName() != null ? user.getLastName() : "")).strip();
        return new ParticipantDTO(userId.toString(), name.isEmpty() ? "Utilisateur" : name, null);
    }

    private String mapBidStatus(BidStatus status) {
        if (status == null) return null;
        return switch (status) {
            case ACCEPTED -> "BID_ACCEPTED";
            case COMPLETED -> "DELIVERY_CONFIRMED";
            case CANCELLED, NO_SHOW, PARCEL_REFUSED -> "TRIP_CANCELLED";
            default -> null;
        };
    }

    private String fullName(UserEntity u) {
        String fn = u.getFirstName() != null ? u.getFirstName() : "";
        String ln = u.getLastName()  != null ? u.getLastName()  : "";
        return (fn + " " + ln).strip();
    }
}
