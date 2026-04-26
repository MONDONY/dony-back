package com.dony.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Transactional SMS for OTP/confirmation codes.
 * In dev, messages are logged. In prod, replace body with Africa's Talking HTTP call.
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    @Value("${app.sms.enabled:false}")
    private boolean smsEnabled;

    public void send(String phoneNumber, String message) {
        if (!smsEnabled) {
            log.info("[SMS-DEV] To={} | {}", phoneNumber, message);
            return;
        }
        // TODO: Africa's Talking HTTP call
        // POST https://api.africastalking.com/version1/messaging
        log.info("[SMS] Sent to {}", phoneNumber);
    }
}
