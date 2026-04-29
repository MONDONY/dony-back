package com.dony.api.admin;

import com.dony.api.auth.UserEntity;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/conversations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminConversationController {

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final AuditService auditService;

    public AdminConversationController(ConversationRepository conversationRepository,
                                       FirestoreService firestoreService,
                                       AuditService auditService) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<Page<ConversationEntity>> listAllConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(conversationRepository.findAll(pageable));
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
}
