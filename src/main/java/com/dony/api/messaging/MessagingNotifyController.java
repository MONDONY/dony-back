package com.dony.api.messaging;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.messaging.dto.NotifyMessageRequest;
import com.dony.api.notifications.NotificationDispatcher;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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

        if (internalSecret == null || internalSecret.isBlank()
                || secret == null
                || !MessageDigest.isEqual(
                        internalSecret.getBytes(StandardCharsets.UTF_8),
                        secret.getBytes(StandardCharsets.UTF_8))) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "invalid-internal-secret", "Unauthorized", "Invalid internal secret");
        }

        var conv = conversationRepository
                .findByFirestoreConversationId(request.conversationId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "conversation-not-found", "Not Found", "Conversation introuvable"));

        String preview = request.messagePreview() != null ? request.messagePreview() : "[Image]";
        notificationDispatcher.sendMessageNotification(
                conv.getSenderId(), conv.getTravelerId(),
                request.senderFirebaseUid(), preview, conv.getFirestoreConversationId());

        return ResponseEntity.noContent().build();
    }
}
