package com.dony.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final String AT_URL = "https://api.africastalking.com/version1/messaging";

    @Value("${app.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${app.sms.africastalking.api-key:}")
    private String atApiKey;

    @Value("${app.sms.africastalking.username:sandbox}")
    private String atUsername;

    @Value("${app.sms.twilio.account-sid:}")
    private String twilioAccountSid;

    @Value("${app.sms.twilio.auth-token:}")
    private String twilioAuthToken;

    @Value("${app.sms.twilio.from:}")
    private String twilioFrom;

    private final RestTemplate restTemplate;

    public SmsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void send(String phoneNumber, String message) {
        if (!smsEnabled) {
            log.info("[SMS-DEV] To=*** | [message redacted]");
            return;
        }
        if (!sendViaAfricasTalking(phoneNumber, message)) {
            log.warn("[SMS] Africa's Talking failed, falling back to Twilio");
            sendViaTwilio(phoneNumber, message);
        }
    }

    boolean sendViaAfricasTalking(String phoneNumber, String message) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("apiKey", atApiKey);
            headers.set("Accept", "application/json");
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("username", atUsername);
            body.add("to", phoneNumber);
            body.add("message", message);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    AT_URL, new HttpEntity<>(body, headers), String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("[SMS] Africa's Talking: delivered successfully");
                return true;
            }
            log.warn("[SMS] Africa's Talking returned HTTP {}", response.getStatusCode());
            return false;
        } catch (Exception e) {
            log.warn("[SMS] Africa's Talking error: {}", e.getMessage());
            return false;
        }
    }

    void sendViaTwilio(String phoneNumber, String message) {
        try {
            String url = "https://api.twilio.com/2010-04-01/Accounts/" + twilioAccountSid + "/Messages.json";

            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(twilioAccountSid, twilioAuthToken);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("To", phoneNumber);
            body.add("From", twilioFrom);
            body.add("Body", message);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("[SMS] Twilio: delivered successfully");
            } else {
                log.error("[SMS] Twilio returned HTTP {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[SMS] Twilio error: {}", e.getMessage());
        }
    }
}