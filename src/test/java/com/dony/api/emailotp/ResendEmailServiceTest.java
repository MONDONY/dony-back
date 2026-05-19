package com.dony.api.emailotp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.*;

@DisplayName("ResendEmailService — tests unitaires")
class ResendEmailServiceTest {

    private RestClient mockRestClient;
    private ResendEmailService service;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        service = new ResendEmailService("noreply@dony.app", "Ton code : %s", mockRestClient);
    }

    @Test
    @DisplayName("sendOtp — exécute la chaîne RestClient vers /emails")
    void sendOtp_callsResendApi() {
        service.sendOtp("user@example.com", "123456");

        verify(mockRestClient).post();
    }
}
