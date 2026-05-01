package com.dony.api.messaging;

import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class FirestoreService {

    private static final Logger log = LoggerFactory.getLogger(FirestoreService.class);

    @Nullable
    private final Firestore firestore;

    public FirestoreService(@Nullable Firestore firestore) {
        this.firestore = firestore;
    }

    public void createConversation(String conversationId, Map<String, Object> data) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping createConversation");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId).set(data).get();
        } catch (Exception e) {
            throw new RuntimeException("Firestore createConversation failed", e);
        }
    }

    public void addSystemMessage(String conversationId, String body) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping addSystemMessage");
            return;
        }
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("senderId", "SYSTEM");
            msg.put("body", body);
            msg.put("imageUrl", null);
            msg.put("type", "SYSTEM");
            msg.put("sentAt", Instant.now().toString());
            msg.put("readAt", null);
            firestore.collection("conversations").document(conversationId)
                     .collection("messages").add(msg).get();
        } catch (Exception e) {
            throw new RuntimeException("Firestore addSystemMessage failed", e);
        }
    }

    public void updateLastMessage(String conversationId, String preview, String sentAt) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping updateLastMessage");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId)
                     .update("lastMessagePreview", preview, "lastMessageAt", sentAt).get();
        } catch (Exception e) {
            log.warn("Firestore updateLastMessage failed: {}", e.getMessage());
        }
    }

    public void softDeleteMessage(String conversationId, String messageId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping softDeleteMessage");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId)
                     .collection("messages").document(messageId)
                     .update("deletedAt", Instant.now().toString()).get();
        } catch (Exception e) {
            throw new RuntimeException("Firestore softDeleteMessage failed", e);
        }
    }

    public void clearConversationDeleted(String conversationId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping clearConversationDeleted");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId)
                     .update("deletedAt", null).get();
        } catch (Exception e) {
            log.warn("Firestore clearConversationDeleted failed: {}", e.getMessage());
        }
    }

    public void markConversationDeleted(String conversationId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping markConversationDeleted");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId)
                     .update("deletedAt", Instant.now().toString()).get();
        } catch (Exception e) {
            log.warn("Firestore markConversationDeleted failed: {}", e.getMessage());
        }
    }

    // Purge définitive : supprime tous les messages puis le document conversation
    public void purgeConversation(String conversationId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping purgeConversation");
            return;
        }
        try {
            var messagesRef = firestore.collection("conversations")
                                       .document(conversationId)
                                       .collection("messages");
            var messages = messagesRef.get().get();
            for (var doc : messages.getDocuments()) {
                doc.getReference().delete().get();
            }
            firestore.collection("conversations").document(conversationId).delete().get();
        } catch (Exception e) {
            log.warn("Firestore purgeConversation failed for {}: {}", conversationId, e.getMessage());
        }
    }
}
