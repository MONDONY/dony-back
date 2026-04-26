package com.dony.api.notifications;

import com.dony.api.auth.UserRepository;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class FcmService {

    private static final Logger log = LoggerFactory.getLogger(FcmService.class);

    private final UserRepository userRepository;

    public FcmService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Send a push notification to a user by userId.
     * Returns true if the message was dispatched, false if the user has no token.
     */
    @Transactional
    public boolean sendToUser(UUID userId, String title, String body, Map<String, String> data) {
        return userRepository.findById(userId)
                .map(user -> {
                    String token = user.getFcmToken();
                    if (token == null || token.isBlank()) {
                        log.debug("[FCM] User {} has no FCM token — skipping", userId);
                        return false;
                    }
                    return sendToToken(token, title, body, data, userId);
                })
                .orElseGet(() -> {
                    log.warn("[FCM] User {} not found", userId);
                    return false;
                });
    }

    public void sendToTopic(String topic, String title, String body) {
        Message message = Message.builder()
                .setTopic(topic)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .setAndroidConfig(AndroidConfig.builder()
                        .setNotification(AndroidNotification.builder()
                                .setChannelId("dony_general")
                                .build())
                        .build())
                .build();

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("[FCM] Topic '{}' → messageId={}", topic, messageId);
        } catch (FirebaseMessagingException e) {
            log.error("[FCM] Topic '{}' send failed: {}", topic, e.getMessage(), e);
        }
    }

    private boolean sendToToken(String token, String title, String body,
                                Map<String, String> data, UUID userId) {
        Message.Builder builder = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setChannelId("dony_transactional")
                                .build())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder()
                                .setSound("default")
                                .setBadge(1)
                                .build())
                        .build());

        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }

        try {
            String messageId = FirebaseMessaging.getInstance().send(builder.build());
            log.info("[FCM] Sent to user={} messageId={}", userId, messageId);
            return true;
        } catch (FirebaseMessagingException e) {
            if ("UNREGISTERED".equals(e.getMessagingErrorCode() != null
                    ? e.getMessagingErrorCode().name() : "")) {
                log.warn("[FCM] Token UNREGISTERED for user={} — clearing token", userId);
                clearToken(userId);
            } else {
                log.error("[FCM] Send failed for user={}: {}", userId, e.getMessage(), e);
            }
            return false;
        }
    }

    @Transactional
    protected void clearToken(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setFcmToken(null);
            userRepository.save(user);
        });
    }
}
