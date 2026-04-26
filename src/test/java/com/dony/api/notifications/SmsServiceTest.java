package com.dony.api.notifications;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsServiceTest {

    @Mock RestTemplate restTemplate;

    SmsService smsService;

    @BeforeEach
    void setUp() {
        smsService = new SmsService(restTemplate);
        ReflectionTestUtils.setField(smsService, "smsEnabled", true);
        ReflectionTestUtils.setField(smsService, "atApiKey", "test-at-key");
        ReflectionTestUtils.setField(smsService, "atUsername", "sandbox");
        ReflectionTestUtils.setField(smsService, "twilioAccountSid", "ACtest");
        ReflectionTestUtils.setField(smsService, "twilioAuthToken", "token");
        ReflectionTestUtils.setField(smsService, "twilioFrom", "+15005550006");
    }

    @Test
    void devMode_logsAndSkipsHttpCall() {
        ReflectionTestUtils.setField(smsService, "smsEnabled", false);
        smsService.send("+221701234567", "Test message");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void send_africasTalkingSuccess_noTwilioFallback() {
        when(restTemplate.postForEntity(contains("africastalking"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        smsService.send("+221701234567", "Livraison confirmée");

        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void send_africasTalkingFails_fallsBackToTwilio() {
        when(restTemplate.postForEntity(contains("africastalking"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error"));
        when(restTemplate.postForEntity(contains("twilio"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        smsService.send("+221701234567", "Paiement reçu");

        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void send_africasTalkingThrows_fallsBackToTwilio() {
        when(restTemplate.postForEntity(contains("africastalking"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));
        when(restTemplate.postForEntity(contains("twilio"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        smsService.send("+221701234567", "Litige ouvert");

        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void sendViaAfricasTalking_includesPhoneAndMessage() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        boolean result = smsService.sendViaAfricasTalking("+221701234567", "Message test");

        assertThat(result).isTrue();
        ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));
        assertThat(captor.getValue().getBody().toString()).contains("221701234567");
    }

    @Test
    void sendViaAfricasTalking_returnsFalseOnError() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("timeout"));

        boolean result = smsService.sendViaAfricasTalking("+221701234567", "msg");

        assertThat(result).isFalse();
    }

    @Test
    void sendViaTwilio_usesTwilioEndpoint() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body("{}"));

        smsService.sendViaTwilio("+221701234567", "Message Twilio");

        verify(restTemplate).postForEntity(contains("twilio.com"), any(), eq(String.class));
    }
}