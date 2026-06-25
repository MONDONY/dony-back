package com.dony.api.admin;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.messaging.ConversationEntity;
import com.dony.api.messaging.ConversationRepository;
import com.dony.api.messaging.FirestoreService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/conversations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminConversationController {

    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final FirestoreService firestoreService;
    private final AuditService auditService;

    public AdminConversationController(ConversationRepository conversationRepository,
                                       UserRepository userRepository,
                                       FirestoreService firestoreService,
                                       AuditService auditService) {
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
        this.firestoreService = firestoreService;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<Page<AdminConversationResponse>> listAllConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ConversationEntity> entities = conversationRepository.findAllByDeletedAtIsNull(pageable);

        Set<UUID> userIds = new HashSet<>();
        entities.forEach(c -> {
            userIds.add(c.getSenderId());
            userIds.add(c.getTravelerId());
        });
        Map<UUID, String> nameMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, this::displayName));

        return ResponseEntity.ok(entities.map(c -> new AdminConversationResponse(
                c.getFirestoreConversationId(),
                c.getBidId(),
                nameMap.getOrDefault(c.getSenderId(), null),
                nameMap.getOrDefault(c.getTravelerId(), null),
                null,
                0,
                false,
                c.getCreatedAt().toString()
        )));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<AdminMessageResponse>> getMessages(@PathVariable String conversationId) {
        List<AdminMessageResponse> messages = firestoreService.getMessages(conversationId).stream()
                .map(m -> new AdminMessageResponse(
                        (String) m.get("id"),
                        conversationId,
                        (String) m.get("senderName"),
                        (String) m.getOrDefault("body", ""),
                        Boolean.TRUE.equals(m.get("flagged")),
                        m.get("deletedAt") != null,
                        (String) m.get("sentAt")
                ))
                .toList();
        return ResponseEntity.ok(messages);
    }

    @DeleteMapping("/{conversationId}/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable String conversationId,
            @PathVariable String messageId,
            @AuthenticationPrincipal UserEntity admin) {

        ConversationEntity conv = conversationRepository
                .findByFirestoreConversationId(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));

        firestoreService.softDeleteMessage(conversationId, messageId);

        auditService.log("message", conv.getId(), "MESSAGE_ADMIN_DELETED", admin.getId(),
                Map.of("conversationId", conversationId, "messageId", messageId));

        return ResponseEntity.noContent().build();
    }

    private String displayName(UserEntity u) {
        if (u.getFirstName() != null && u.getLastName() != null) {
            return u.getFirstName() + " " + u.getLastName();
        }
        return u.getPhoneNumber() != null ? u.getPhoneNumber() : u.getId().toString();
    }
}
