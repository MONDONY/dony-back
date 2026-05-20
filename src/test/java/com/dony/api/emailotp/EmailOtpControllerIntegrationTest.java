package com.dony.api.emailotp;

import com.dony.api.common.DonyBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dony.api.emailotp.dto.EmailOtpSendRequest;
import com.dony.api.emailotp.dto.EmailOtpVerifyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("EmailOtpController — intégration MockMvc")
class EmailOtpControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private EmailOtpService emailOtpService;

    // ─── POST /auth/email-otp/send ────────────────────────────────────────────

    @Test
    @DisplayName("send 200 — retourne expiresAt")
    void send_success() throws Exception {
        Instant expiry = Instant.parse("2026-05-19T10:15:00Z");
        when(emailOtpService.sendOtp("user@example.com")).thenReturn(expiry);

        mockMvc.perform(post("/auth/email-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new EmailOtpSendRequest("user@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value("2026-05-19T10:15:00Z"));
    }

    @Test
    @DisplayName("send 422 — email invalide")
    void send_invalidEmail() throws Exception {
        mockMvc.perform(post("/auth/email-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("send 429 — rate-limit atteint")
    void send_rateLimitExceeded() throws Exception {
        when(emailOtpService.sendOtp(anyString()))
                .thenThrow(new DonyBusinessException(
                        HttpStatus.TOO_MANY_REQUESTS, "rate-limit",
                        "Too Many Requests", "Trop de tentatives, réessaie dans 5 min"));

        mockMvc.perform(post("/auth/email-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new EmailOtpSendRequest("user@example.com"))))
                .andExpect(status().isTooManyRequests());
    }

    // ─── POST /auth/email-otp/verify ──────────────────────────────────────────

    @Test
    @DisplayName("verify 200 — retourne customToken")
    void verify_success() throws Exception {
        when(emailOtpService.verifyOtp("user@example.com", "123456"))
                .thenReturn("firebase-custom-token");

        mockMvc.perform(post("/auth/email-otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new EmailOtpVerifyRequest("user@example.com", "123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customToken").value("firebase-custom-token"));
    }

    @Test
    @DisplayName("verify 400 — code invalide")
    void verify_invalidCode() throws Exception {
        when(emailOtpService.verifyOtp(anyString(), anyString()))
                .thenThrow(new DonyBusinessException(
                        HttpStatus.BAD_REQUEST, "otp-invalid",
                        "Invalid OTP", "Code invalide"));

        mockMvc.perform(post("/auth/email-otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new EmailOtpVerifyRequest("user@example.com", "000000"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("verify 429 — trop de tentatives")
    void verify_tooManyAttempts() throws Exception {
        when(emailOtpService.verifyOtp(anyString(), anyString()))
                .thenThrow(new DonyBusinessException(
                        HttpStatus.TOO_MANY_REQUESTS, "otp-attempts-exceeded",
                        "Too Many Attempts", "Trop de tentatives"));

        mockMvc.perform(post("/auth/email-otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new EmailOtpVerifyRequest("user@example.com", "000000"))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("verify 422 — code non numérique")
    void verify_nonNumericCode() throws Exception {
        mockMvc.perform(post("/auth/email-otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"code\":\"abcdef\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("send 422 — email absent (body vide)")
    void send_missingEmail() throws Exception {
        mockMvc.perform(post("/auth/email-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
