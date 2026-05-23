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
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
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
        // Return the conversation if it's still visible to the requesting user
        Optional<ConversationEntity> accessible =
            conversationRepository.findByBidIdAndParticipant(bidId, requestingUserId);
        if (accessible.isPresent()) {
            return accessible.get();
        }

        // The requesting user deleted their copy — return it anyway with deletedBySelf flag
        // so the caller can offer a "Restore" option instead of throwing GONE.
        Optional<ConversationEntity> deletedBySelf =
            conversationRepository.findByBidIdAndParticipantIgnoreDeleted(bidId, requestingUserId);
        if (deletedBySelf.isPresent()) {
            return deletedBySelf.get();
        }

        // No conversation yet → create one (verify the user belongs to this bid)
        BidEntity bid = bidRepository.findById(bidId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bid not found"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Announcement not found"));

        UUID senderId  = bid.getSenderId();
        UUID travelerId = announcement.getTravelerId();

        if (!requestingUserId.equals(senderId) && !requestingUserId.equals(travelerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied");
        }

        return createConversationForBid(bidId, senderId, travelerId);
    }

    @Transactional
    public ConversationEntity restoreConversation(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipantIgnoreDeleted(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));

        if (!conv.isDeletedByUser(requestingUserId)) {
            return conv; // Nothing to restore
        }

        conv.restoreForUser(requestingUserId);
        conversationRepository.save(conv);

        auditService.log("conversation", conversationId, "CONVERSATION_RESTORED", requestingUserId,
            Map.of("firestoreId", conv.getFirestoreConversationId()));

        // Clear Firestore deletedAt so the other party's read-only state clears on next load
        try {
            firestoreService.clearConversationDeleted(conv.getFirestoreConversationId());
        } catch (Exception e) {
            log.warn("Firestore clearConversationDeleted failed for {}: {}", conversationId, e.getMessage());
        }

        return conv;
    }

    @Transactional
    public ConversationEntity createConversationForBid(UUID bidId, UUID senderId, UUID travelerId) {
        return conversationRepository.findByBidId(bidId).orElseGet(() -> {
            String firestoreId = "conv_" + bidId;

            UserEntity sender   = userRepository.findById(senderId).orElseThrow();
            UserEntity traveler = userRepository.findById(travelerId).orElseThrow();

            ConversationEntity entity = new ConversationEntity(bidId, senderId, travelerId, firestoreId);
            ConversationEntity saved  = conversationRepository.save(entity);

            auditService.log("conversation", saved.getId(), "CONVERSATION_CREATED", senderId,
                Map.of("bidId", bidId.toString(), "firestoreId", firestoreId));

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

        conv.deleteForUser(requestingUserId);

        // Les deux parties ont supprimé → purge définitive
        if (conv.getSenderDeletedAt() != null && conv.getTravelerDeletedAt() != null) {
            conversationRepository.delete(conv);
            auditService.log("conversation", conversationId, "CONVERSATION_PURGED", requestingUserId,
                Map.of("firestoreId", conv.getFirestoreConversationId()));
            try {
                firestoreService.purgeConversation(conv.getFirestoreConversationId());
            } catch (Exception e) {
                log.warn("Firestore purgeConversation failed for {}: {}", conversationId, e.getMessage());
            }
            return;
        }

        conversationRepository.save(conv);
        auditService.log("conversation", conversationId, "CONVERSATION_DELETED", requestingUserId,
            Map.of("firestoreId", conv.getFirestoreConversationId()));

        try {
            firestoreService.markConversationDeleted(conv.getFirestoreConversationId());
        } catch (Exception e) {
            log.warn("Firestore markConversationDeleted failed for {}: {}", conversationId, e.getMessage());
        }
    }

    @Transactional
    public void archiveConversation(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipantIgnoreArchived(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));
        conv.archiveForUser(requestingUserId);
        conversationRepository.save(conv);
        auditService.log("conversation", conversationId, "CONVERSATION_ARCHIVED", requestingUserId, Map.of());
    }

    @Transactional
    public void unarchiveConversation(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipantIgnoreArchived(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));
        if (!conv.isArchivedByUser(requestingUserId)) {
            return;
        }
        conv.unarchiveForUser(requestingUserId);
        conversationRepository.save(conv);
        auditService.log("conversation", conversationId, "CONVERSATION_UNARCHIVED", requestingUserId, Map.of());
    }

    public List<ConversationResponse> getArchivedConversations(UUID userId) {
        return conversationRepository
            .findArchivedByParticipant(userId, Pageable.unpaged())
            .stream()
            .map(c -> toResponse(c, userId))
            .toList();
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

        String tripOrigin      = null;
        String tripDestination = null;
        String tripDate        = null;
        Double tripWeightKg    = null;
        String bidStatus       = null;

        Optional<BidEntity> bidOpt = bidRepository.findById(conv.getBidId());
        if (bidOpt.isPresent()) {
            BidEntity bid = bidOpt.get();
            tripWeightKg = bid.getWeightKg() != null ? bid.getWeightKg().doubleValue() : null;
            bidStatus    = mapBidStatus(bid.getStatus());

            Optional<AnnouncementEntity> annOpt = announcementRepository.findById(bid.getAnnouncementId());
            if (annOpt.isPresent()) {
                AnnouncementEntity ann = annOpt.get();
                tripOrigin      = ann.getDepartureCity();
                tripDestination = ann.getArrivalCity();
                tripDate        = ann.getDepartureDate() != null ? ann.getDepartureDate().toString() : null;
            }
        }

        return new ConversationResponse(
            conv.getId(),
            conv.getBidId(),
            conv.getFirestoreConversationId(),
            otherParticipant,
            null,   // lastMessagePreview — lives in Firestore
            conv.getUpdatedAt(),
            false,  // hasUnread — determined client-side via Firestore
            tripOrigin,
            tripDestination,
            tripDate,
            tripWeightKg,
            bidStatus,
            conv.isReadOnlyFor(currentUserId),
            conv.isDeletedByUser(currentUserId)
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
