package com.dony.api.messaging;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.PageResponse;
import com.dony.api.common.StorageService;
import com.dony.api.messaging.dto.ConversationResponse;
import com.dony.api.messaging.dto.ImageUploadResponse;
import com.dony.api.messaging.dto.LastMessageRequest;
import com.dony.api.messaging.dto.ParticipantDTO;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/conversations")
@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
public class ConversationController {

    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5 MB

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final UserRepository userRepository;
    private final StorageService storageService;

    public ConversationController(ConversationRepository conversationRepository,
                                   ConversationService conversationService,
                                   UserRepository userRepository,
                                   StorageService storageService) {
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    // GET /conversations — paginated list for the authenticated user
    @GetMapping
    public ResponseEntity<PageResponse<ConversationResponse>> listConversations(
            @PageableDefault(size = 20) Pageable pageable) {

        UserEntity currentUser = resolveCurrentUser();
        Page<ConversationEntity> page = conversationRepository
                .findByParticipant(currentUser.getId(), pageable);

        Page<ConversationResponse> responsePage = page.map(c -> toResponse(c, currentUser.getId()));
        return ResponseEntity.ok(PageResponse.from(responsePage));
    }

    // GET /conversations/{id} — single conversation
    @GetMapping("/{id}")
    public ResponseEntity<ConversationResponse> getConversation(
            @PathVariable UUID id) {

        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationRepository
                .findByIdAndParticipant(id, currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));

        return ResponseEntity.ok(toResponse(conv, currentUser.getId()));
    }

    // GET /conversations/bid/{bidId} — conversation liée à un bid (get or create)
    @GetMapping("/bid/{bidId}")
    public ResponseEntity<ConversationResponse> getConversationByBidId(
            @PathVariable UUID bidId) {

        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationService.getOrCreateByBidId(bidId, currentUser.getId());
        return ResponseEntity.ok(toResponse(conv, currentUser.getId()));
    }

    // POST /conversations/{id}/last-message — update Firestore last message preview
    @PostMapping("/{id}/last-message")
    public ResponseEntity<Void> updateLastMessage(
            @PathVariable UUID id,
            @Valid @RequestBody LastMessageRequest body) {

        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationRepository
                .findByIdAndParticipant(id, currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));

        conversationService.updateLastMessage(conv.getFirestoreConversationId(), body.preview());

        return ResponseEntity.noContent().build();
    }

    // POST /conversations/{id}/upload — upload image to S3
    @PostMapping("/{id}/upload")
    public ResponseEntity<ImageUploadResponse> uploadImage(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {

        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationRepository
                .findByIdAndParticipant(id, currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));

        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "File exceeds 5MB limit");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only image files are allowed");
        }

        String prefix = "messaging/" + conv.getFirestoreConversationId() + "/";
        String key;
        try {
            key = storageService.uploadFile(file, prefix);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload file");
        }

        String presignedUrl = storageService.generatePresignedUrl(key, Duration.ofDays(7));
        return ResponseEntity.ok(new ImageUploadResponse(presignedUrl, key));
    }

    // Helper: resolve the current authenticated user from the SecurityContext (UID-based)
    private UserEntity resolveCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String uid = (String) auth.getPrincipal();
        return userRepository.findByFirebaseUid(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "User not found for uid: " + uid));
    }

    // Helper: build ConversationResponse, resolving the "other" participant
    private ConversationResponse toResponse(ConversationEntity conv, UUID currentUserId) {
        UUID otherUserId = conv.getSenderId().equals(currentUserId)
                ? conv.getTravelerId()
                : conv.getSenderId();

        UserEntity other = userRepository.findById(otherUserId).orElse(null);
        ParticipantDTO otherParticipant = buildParticipant(otherUserId, other);

        return new ConversationResponse(
                conv.getId(),
                conv.getBidId(),
                conv.getFirestoreConversationId(),
                otherParticipant,
                null,           // lastMessagePreview — lives in Firestore, not in SQL
                conv.getUpdatedAt(),
                false           // hasUnread — determined client-side via Firestore
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
}
