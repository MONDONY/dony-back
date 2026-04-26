package com.dony.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class FcmService {

    private static final Logger log = LoggerFactory.getLogger(FcmService.class);

    /**
     * Send a silent data message to trigger a refresh on the recipient's device.
     * In dev, messages are logged. In prod, replace with FirebaseMessaging.getInstance().send().
     */
    public void sendDataMessage(String fcmToken, String type, Map<String, String> data) {
        if (fcmToken == null || fcmToken.isBlank()) {
            log.debug("[FCM] No token — skipping message type={}", type);
            return;
        }
        log.info("[FCM-DEV] token={} | type={} | data={}", fcmToken, type, data);
        // TODO prod: FirebaseMessaging.getInstance().send(Message.builder()
        //     .setToken(fcmToken)
        //     .putAllData(data)
        //     .build());
    }
}
