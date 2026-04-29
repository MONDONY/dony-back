package com.dony.api.messaging;

import com.dony.api.messaging.dto.NotifyMessageRequest;
import com.dony.api.notifications.NotificationDispatcher;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/messaging")
public class MessagingNotifyController {

    @Value("${dony.internal.secret:}")
    private String internalSecret;

    private final ConversationRepository conversationRepository;
    private final NotificationDispatcher notificationDispatcher;

    public MessagingNotifyController(ConversationRepository conversationRepository,
                                      NotificationDispatcher notificationDispatcher) {
        this.conversationRepository = conversationRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @PostMapping("/notify")
    public ResponseEntity<Void> notify(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret,
            @Valid @RequestBody NotifyMessageRequest request) {

        if (internalSecret.isBlank() || !internalSecret.equals(secret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal secret");
        }

        var conv = conversationRepository.findAll().stream()
                .filter(c -> c.getFirestoreConversationId().equals(request.conversationId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));

        String preview = request.messagePreview() != null ? request.messagePreview() : "[Image]";
        notificationDispatcher.sendMessageNotification(
                conv.getSenderId(), conv.getTravelerId(),
                request.senderFirebaseUid(), preview, conv.getFirestoreConversationId());

        return ResponseEntity.noContent().build();
    }
}
