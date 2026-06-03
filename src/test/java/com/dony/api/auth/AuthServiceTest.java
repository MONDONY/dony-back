package com.dony.api.auth;

import com.dony.api.auth.dto.RegisterRequest;
import com.dony.api.auth.dto.UpdateProfileRequest;
import com.dony.api.auth.dto.UserResponse;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.PaymentRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — tests unitaires")
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private UserService userService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private AccountFinalizationService accountFinalizationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private AuthService authService;

    private static final String FIREBASE_UID = "uid-test-123";
    private static final String PHONE = "+33612345678";

    // ─── Helper ────────────────────────────────────────────────────────────────

    private UserEntity buildUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(FIREBASE_UID);
        u.setPhoneNumber(PHONE);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.getRoles().add(Role.SENDER);
        setId(u, UUID.randomUUID());
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private com.google.firebase.auth.FirebaseToken mockPhoneToken() {
        com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
        lenient().when(token.getClaims()).thenReturn(java.util.Map.of(
                "firebase", java.util.Map.of("sign_in_provider", "phone")));
        return token;
    }

    // ─── register ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("register()")
    class RegisterTests {

        @Test
        @DisplayName("utilisateur déjà inscrit → retourne le profil existant")
        void register_existingUser_returnsExisting() {
            UserEntity existing = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(existing));

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            UserResponse result = authService.register(FIREBASE_UID, mockPhoneToken(), req);

            assertThat(result.phoneNumber()).isEqualTo(PHONE);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("nouvel utilisateur valide → crée l'utilisateur en base")
        void register_newUser_createsUser() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("TRAVELER"));
            UserResponse result = authService.register(FIREBASE_UID, mockPhoneToken(), req);

            assertThat(result.phoneNumber()).isEqualTo(PHONE);
            assertThat(result.kycStatus()).isEqualTo("NOT_STARTED");
            assertThat(result.status()).isEqualTo("ACTIVE");
            verify(userRepository).save(any(UserEntity.class));
            verify(auditService).log(eq("USER"), any(), eq("USER_CREATED"), any(), any());
        }

        @Test
        @DisplayName("rôle ADMIN dans la requête → ignoré, compte créé avec SENDER seulement")
        void register_adminRole_ignored_createsSenderOnly() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("ADMIN"));
            authService.register(FIREBASE_UID, mockPhoneToken(), req);

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles()).containsExactly(Role.SENDER);
        }

        @Test
        @DisplayName("numéro déjà utilisé → 409 CONFLICT")
        void register_duplicatePhone_throwsConflict() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(true);

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, mockPhoneToken(), req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("phone-already-exists");
                    });
        }

        @ParameterizedTest
        @ValueSource(strings = {"INVALID", "SUPERUSER", "ROOT"})
        @DisplayName("rôles non reconnus dans la requête → ignorés, compte créé avec SENDER seulement")
        void register_unknownRoles_ignored_createsSenderOnly(String role) {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of(role));
            authService.register(FIREBASE_UID, mockPhoneToken(), req);

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles()).containsExactly(Role.SENDER);
        }

        @Test
        @DisplayName("SENDER+TRAVELER dans la requête → ignorés, seul SENDER est enregistré")
        void register_dualRoles_ignored_senderOnly() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER", "TRAVELER"));
            authService.register(FIREBASE_UID, mockPhoneToken(), req);

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles())
                    .containsExactly(Role.SENDER)
                    .doesNotContain(Role.TRAVELER);
        }
    }

    // ─── getProfile ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getProfile()")
    class GetProfileTests {

        @Test
        @DisplayName("utilisateur existant → retourne le profil complet")
        void getProfile_existingUser_returnsUserResponse() {
            UserEntity user = buildUser();
            user.setEmail("test@dony.app");
            user.setFirstName("Amadou");
            user.setLastName("Diallo");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            UserResponse result = authService.getProfile(FIREBASE_UID);

            assertThat(result.phoneNumber()).isEqualTo(PHONE);
            assertThat(result.email()).isEqualTo("test@dony.app");
            assertThat(result.firstName()).isEqualTo("Amadou");
            assertThat(result.lastName()).isEqualTo("Diallo");
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void getProfile_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.getProfile(FIREBASE_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
                    });
        }
    }

    // ─── updateProfile ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateProfile()")
    class UpdateProfileTests {

        @Test
        @DisplayName("mise à jour tous les champs → profil modifié")
        void updateProfile_allFields_updatesUser() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(UserEntity.class))).thenReturn(user);

            UpdateProfileRequest req = new UpdateProfileRequest(
                    "Amadou", "Diallo", "amadou@dony.app",
                    LocalDate.of(1990, 5, 15), "Paris", null
            );

            UserResponse result = authService.updateProfile(FIREBASE_UID, req);

            assertThat(user.getFirstName()).isEqualTo("Amadou");
            assertThat(user.getLastName()).isEqualTo("Diallo");
            assertThat(user.getEmail()).isEqualTo("amadou@dony.app");
            assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1990, 5, 15));
            assertThat(user.getCity()).isEqualTo("Paris");
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("champs vides → valeurs nulles en base")
        void updateProfile_emptyStrings_setsNullValues() {
            UserEntity user = buildUser();
            user.setFirstName("Ancien Prénom");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            UpdateProfileRequest req = new UpdateProfileRequest("  ", null, null, null, "  ", null);
            authService.updateProfile(FIREBASE_UID, req);

            assertThat(user.getFirstName()).isNull();
            assertThat(user.getCity()).isNull();
        }

        @Test
        @DisplayName("champs null → valeurs non modifiées")
        void updateProfile_nullFields_keepsExistingValues() {
            UserEntity user = buildUser();
            user.setFirstName("Original");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            UpdateProfileRequest req = new UpdateProfileRequest(null, null, null, null, null, null);
            authService.updateProfile(FIREBASE_UID, req);

            assertThat(user.getFirstName()).isEqualTo("Original");
        }

        @Test
        @DisplayName("ajout numéro de téléphone → sauvegardé en base")
        void updateProfile_addPhoneNumber_saved() {
            UserEntity user = buildUser(); // phone = PHONE = "+33612345678"
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.existsByPhoneNumber("+33699000001")).thenReturn(false);
            when(userRepository.save(any())).thenReturn(user);

            authService.updateProfile(FIREBASE_UID,
                    new UpdateProfileRequest(null, null, null, null, null, "+33699000001"));

            assertThat(user.getPhoneNumber()).isEqualTo("+33699000001");
        }

        @Test
        @DisplayName("numéro déjà pris → 409 CONFLICT")
        void updateProfile_phoneAlreadyTaken_throws409() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.existsByPhoneNumber("+33699999999")).thenReturn(true);

            assertThatThrownBy(() -> authService.updateProfile(FIREBASE_UID,
                    new UpdateProfileRequest(null, null, null, null, null, "+33699999999")))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void updateProfile_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.updateProfile(FIREBASE_UID,
                    new UpdateProfileRequest("A", null, null, null, null, null)))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ─── analytics consent ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAnalyticsConsent()")
    class GetAnalyticsConsentTests {

        @Test
        @DisplayName("utilisateur a répondu → granted/consentAt/version retournés")
        void getAnalyticsConsent_answered_returnsValues() {
            UserEntity user = buildUser();
            java.time.Instant at = java.time.Instant.parse("2026-06-03T04:55:08.960Z");
            user.setAnalyticsConsent(true);
            user.setAnalyticsConsentAt(at);
            user.setAnalyticsConsentVersion("1.0");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            com.dony.api.auth.dto.AnalyticsConsentResponse resp =
                    authService.getAnalyticsConsent(FIREBASE_UID);

            assertThat(resp.granted()).isTrue();
            assertThat(resp.consentAt()).isEqualTo("2026-06-03T04:55:08.960Z");
            assertThat(resp.policyVersion()).isEqualTo("1.0");
        }

        @Test
        @DisplayName("utilisateur n'a jamais répondu → tout null")
        void getAnalyticsConsent_neverAnswered_returnsNulls() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            com.dony.api.auth.dto.AnalyticsConsentResponse resp =
                    authService.getAnalyticsConsent(FIREBASE_UID);

            assertThat(resp.granted()).isNull();
            assertThat(resp.consentAt()).isNull();
            assertThat(resp.policyVersion()).isNull();
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void getAnalyticsConsent_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.getAnalyticsConsent(FIREBASE_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
                    });
        }
    }

    @Nested
    @DisplayName("updateAnalyticsConsent()")
    class UpdateAnalyticsConsentTests {

        @Test
        @DisplayName("met à jour les colonnes + écrit une entrée audit_log avec payload non-null")
        void updateAnalyticsConsent_setsColumns_andLogsAudit() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            authService.updateAnalyticsConsent(FIREBASE_UID, true, "1.0", "manual");

            assertThat(user.getAnalyticsConsent()).isTrue();
            assertThat(user.getAnalyticsConsentAt()).isNotNull();
            assertThat(user.getAnalyticsConsentVersion()).isEqualTo("1.0");
            assertThat(user.getAnalyticsConsentSource()).isEqualTo("manual");
            verify(userRepository).save(user);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(eq("USER"), eq(user.getId()),
                    eq("ANALYTICS_CONSENT_UPDATED"), eq(user.getId()), payloadCaptor.capture());
            java.util.Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).isNotNull();
            assertThat(payload.get("granted")).isEqualTo(true);
            assertThat(payload.get("policyVersion")).isEqualTo("1.0");
            assertThat(payload.get("source")).isEqualTo("manual");
        }

        @Test
        @DisplayName("policyVersion et source null → payload audit utilise des valeurs non-null")
        void updateAnalyticsConsent_nullOptionals_payloadNonNull() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            authService.updateAnalyticsConsent(FIREBASE_UID, false, null, null);

            assertThat(user.getAnalyticsConsent()).isFalse();
            assertThat(user.getAnalyticsConsentVersion()).isNull();
            assertThat(user.getAnalyticsConsentSource()).isNull();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(eq("USER"), eq(user.getId()),
                    eq("ANALYTICS_CONSENT_UPDATED"), eq(user.getId()), payloadCaptor.capture());
            java.util.Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload.get("granted")).isEqualTo(false);
            assertThat(payload.get("policyVersion")).isNotNull();
            assertThat(payload.get("source")).isNotNull();
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND, aucun audit")
        void updateAnalyticsConsent_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.updateAnalyticsConsent(FIREBASE_UID, true, "1.0", "manual"))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }
    }

    // ─── deleteAccount ─────────────────────────────────────────────────────────
    // Full GDPR logic tested in UserServiceTest; AuthService delegates to UserService.

    @Nested
    @DisplayName("deleteAccount()")
    class DeleteAccountTests {

        @Test
        @DisplayName("délègue à UserService.deleteAccount()")
        void deleteAccount_delegatesToUserService() {
            doNothing().when(userService).deleteAccount(FIREBASE_UID);

            authService.deleteAccount(FIREBASE_UID);

            verify(userService).deleteAccount(FIREBASE_UID);
        }
    }

    // ─── toResponse ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toResponse()")
    class ToResponseTests {

        @Test
        @DisplayName("entité complète → UserResponse correctement mappé")
        void toResponse_fullEntity_mapsAllFields() {
            UserEntity user = buildUser();
            user.setEmail("test@example.com");
            user.setFirstName("Fatou");
            user.setLastName("Sow");
            user.setBirthDate(LocalDate.of(1985, 3, 10));
            user.setCity("Lyon");
            user.setKycStatus(KycStatus.VERIFIED);
            user.setStatus(UserStatus.ACTIVE);

            UserResponse resp = authService.toResponse(user);

            assertThat(resp.email()).isEqualTo("test@example.com");
            assertThat(resp.firstName()).isEqualTo("Fatou");
            assertThat(resp.lastName()).isEqualTo("Sow");
            assertThat(resp.birthDate()).isEqualTo(LocalDate.of(1985, 3, 10));
            assertThat(resp.city()).isEqualTo("Lyon");
            assertThat(resp.kycStatus()).isEqualTo("VERIFIED");
            assertThat(resp.status()).isEqualTo("ACTIVE");
            assertThat(resp.roles()).contains("SENDER");
            // PRO fields — new in PR-1 review fix
            assertThat(resp.isProAccount()).isFalse();
            assertThat(resp.stripeAccountStatus()).isNotNull();
            assertThat(resp.country()).isEqualTo("FR");
        }
    }

    // ─── register — routing par provider Firebase ──────────────────────────────

    @Nested
    @DisplayName("register — routing par provider Firebase")
    class RegisterWithProvider {

        private com.google.firebase.auth.FirebaseToken mockToken(String signInProvider, String email) {
            com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
            when(token.getClaims()).thenReturn(java.util.Map.of(
                    "firebase", java.util.Map.of("sign_in_provider", signInProvider)));
            if (email != null) when(token.getEmail()).thenReturn(email);
            return token;
        }

        @Test
        @DisplayName("provider phone — phoneNumber null → 422")
        void phone_phoneNumberRequired() {
            com.google.firebase.auth.FirebaseToken token = mockToken("phone", null);
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, token, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .extracting(e -> ((DonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("provider phone — succès")
        void phone_success() {
            com.google.firebase.auth.FirebaseToken token = mockToken("phone", null);
            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(i -> {
                UserEntity u = i.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            UserResponse result = authService.register(FIREBASE_UID, token, req);

            assertThat(result).isNotNull();
            verify(userRepository).save(argThat(u -> PHONE.equals(u.getPhoneNumber())));
        }

        @Test
        @DisplayName("provider google.com — email depuis token Firebase")
        void google_emailFromToken() {
            com.google.firebase.auth.FirebaseToken token = mockToken("google.com", "google@gmail.com");
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.existsByEmail("google@gmail.com")).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(i -> {
                UserEntity u = i.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            UserResponse result = authService.register(FIREBASE_UID, token, req);

            assertThat(result).isNotNull();
            verify(userRepository).save(argThat(u -> "google@gmail.com".equals(u.getEmail())));
        }

        @Test
        @DisplayName("provider custom (email OTP) — email depuis body")
        void custom_emailFromBody() {
            com.google.firebase.auth.FirebaseToken token = mockToken("custom", null);
            RegisterRequest req = new RegisterRequest(null, "otp@example.com", Set.of("SENDER"));
            when(userRepository.findByFirebaseUid("otp@example.com")).thenReturn(Optional.empty());
            when(userRepository.existsByEmail("otp@example.com")).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(i -> {
                UserEntity u = i.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            UserResponse result = authService.register("otp@example.com", token, req);

            assertThat(result).isNotNull();
            verify(userRepository).save(argThat(u -> "otp@example.com".equals(u.getEmail())));
        }

        @Test
        @DisplayName("provider inconnu → 422")
        void unknownProvider_422() {
            com.google.firebase.auth.FirebaseToken token = mockToken("password", null);
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, token, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .extracting(e -> ((DonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("provider custom — email body ≠ UID token → 422 email-mismatch")
        void custom_emailMismatch_rejected() {
            // L'UID du custom token est "real@firebase.com" mais le body envoie un autre email
            com.google.firebase.auth.FirebaseToken token = mockToken("custom", null);
            RegisterRequest req = new RegisterRequest(null, "spoofed@evil.com", Set.of("SENDER"));
            when(userRepository.findByFirebaseUid("real@firebase.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register("real@firebase.com", token, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("email-mismatch");
                    });
        }

        @Test
        @DisplayName("provider google.com — email null dans le token → 422 email-required")
        void google_nullEmailInToken_throws() {
            com.google.firebase.auth.FirebaseToken token = mockToken("google.com", null);
            // getEmail() non stubbé → retourne null par défaut
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, token, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("email-required");
                    });
        }
    }

    // ─── SENDER-par-défaut ─────────────────────────────────────────────────────

    @Test
    @DisplayName("createUser force SENDER uniquement, ignore request.roles=[TRAVELER,SENDER]")
    void createUser_forcesSenderOnly_ignoringRequestRoles() {
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUidIncludingDeleted(FIREBASE_UID)).thenReturn(Optional.empty());
        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);

        UserEntity saved = new UserEntity();
        saved.setFirebaseUid(FIREBASE_UID);
        saved.setStatus(UserStatus.ACTIVE);
        saved.setKycStatus(KycStatus.NOT_STARTED);
        saved.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER)));
        saved.setPhoneNumber(PHONE);
        setId(saved, UUID.randomUUID());
        when(userRepository.save(any())).thenReturn(saved);

        RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("TRAVELER", "SENDER"));
        UserResponse result = authService.register(FIREBASE_UID, mockPhoneToken(), req);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRoles()).containsExactly(Role.SENDER);
        assertThat(captor.getValue().getRoles()).doesNotContain(Role.TRAVELER);
    }

    @Test
    @DisplayName("register reactivation force SENDER uniquement, ignore request.roles=[TRAVELER]")
    void register_reactivation_forcesSenderOnly() {
        UserEntity deleted = buildUser();
        deleted.getRoles().add(Role.TRAVELER);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUidIncludingDeleted(FIREBASE_UID)).thenReturn(Optional.of(deleted));

        UserEntity reactivated = new UserEntity();
        reactivated.setFirebaseUid(FIREBASE_UID);
        reactivated.setStatus(UserStatus.ACTIVE);
        reactivated.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER)));
        setId(reactivated, UUID.randomUUID());
        when(userRepository.findByFirebaseUid(FIREBASE_UID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(reactivated));
        when(userRepository.save(any())).thenReturn(reactivated);

        RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("TRAVELER"));
        authService.register(FIREBASE_UID, mockPhoneToken(), req);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRoles()).containsExactly(Role.SENDER);
    }
}
