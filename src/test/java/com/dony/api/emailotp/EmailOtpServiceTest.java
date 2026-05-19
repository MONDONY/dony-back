package com.dony.api.emailotp;

import com.dony.api.common.DonyBusinessException;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailOtpService — tests unitaires")
class EmailOtpServiceTest {

    @Mock private EmailOtpRepository emailOtpRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ResendEmailService resendEmailService;
    @Mock private FirebaseAuth firebaseAuth;
    @InjectMocks private EmailOtpService emailOtpService;

    private static final String EMAIL = "test@example.com";

    @Nested
    @DisplayName("sendOtp")
    class SendOtp {

        @Test
        @DisplayName("succès — sauvegarde token et envoie email")
        void success() {
            when(emailOtpRepository.countByEmailSince(eq(EMAIL), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var result = emailOtpService.sendOtp(EMAIL);

            assertThat(result).isNotNull();
            verify(emailOtpRepository).save(argThat(e ->
                    EMAIL.equals(e.getEmail()) && "$2a$10$hashed".equals(e.getCodeHash())));
            verify(resendEmailService).sendOtp(eq(EMAIL), argThat(code ->
                    code.matches("\\d{6}")));
        }

        @Test
        @DisplayName("429 — 3 envois ou plus dans la fenêtre de 5 min")
        void rateLimitExceeded() {
            when(emailOtpRepository.countByEmailSince(eq(EMAIL), any())).thenReturn(3L);

            assertThatThrownBy(() -> emailOtpService.sendOtp(EMAIL))
                    .isInstanceOf(DonyBusinessException.class)
                    .extracting(e -> ((DonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            verify(emailOtpRepository, never()).save(any());
            verify(resendEmailService, never()).sendOtp(any(), any());
        }
    }

    @Nested
    @DisplayName("verifyOtp")
    class VerifyOtp {

        private EmailOtpEntity validToken() {
            EmailOtpEntity t = new EmailOtpEntity();
            t.setEmail(EMAIL);
            t.setCodeHash("$2a$10$hash");
            t.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            t.setAttempts(0);
            return t;
        }

        @Test
        @DisplayName("succès — retourne customToken Firebase")
        void success() throws Exception {
            EmailOtpEntity token = validToken();
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);
            when(firebaseAuth.createCustomToken(EMAIL)).thenReturn("firebase-custom-token");
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            String result = emailOtpService.verifyOtp(EMAIL, "123456");

            assertThat(result).isEqualTo("firebase-custom-token");
            assertThat(token.getUsedAt()).isNotNull();
        }

        @Test
        @DisplayName("400 — aucun token non utilisé")
        void noTokenFound() {
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(DonyBusinessException.class)
                    .extracting(e -> ((DonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("429 — trop de tentatives échouées")
        void tooManyAttempts() {
            EmailOtpEntity token = validToken();
            token.setAttempts(5);
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(DonyBusinessException.class)
                    .extracting(e -> ((DonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("400 — token expiré")
        void tokenExpired() {
            EmailOtpEntity token = validToken();
            token.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(DonyBusinessException.class)
                    .extracting(e -> ((DonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("400 — code BCrypt invalide, incrémente attempts")
        void invalidCode() {
            EmailOtpEntity token = validToken();
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("000000", "$2a$10$hash")).thenReturn(false);
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "000000"))
                    .isInstanceOf(DonyBusinessException.class)
                    .extracting(e -> ((DonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            assertThat(token.getAttempts()).isEqualTo(1);
        }
    }
}
