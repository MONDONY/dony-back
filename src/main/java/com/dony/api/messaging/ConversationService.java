package com.dony.api.messaging;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

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

    public void updateLastMessage(String firestoreConversationId, String preview) {
        firestoreService.updateLastMessage(firestoreConversationId, preview, Instant.now().toString());
    }

    private String fullName(UserEntity u) {
        String fn = u.getFirstName() != null ? u.getFirstName() : "";
        String ln = u.getLastName()  != null ? u.getLastName()  : "";
        return (fn + " " + ln).strip();
    }
}
