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
        @DisplayName("rôle ADMIN auto-attribué → 403 FORBIDDEN")
        void register_adminRole_throwsForbidden() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("ADMIN"));

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, mockPhoneToken(), req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("forbidden-role");
                    });
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
        @DisplayName("rôle invalide → 422 UNPROCESSABLE_ENTITY")
        void register_invalidRole_throwsUnprocessable(String role) {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of(role));

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, mockPhoneToken(), req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("invalid-role");
                    });
        }

        @Test
        @DisplayName("rôles SENDER+TRAVELER → les deux rôles sont enregistrés")
        void register_dualRoles_savesBothRoles() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER", "TRAVELER"));
            UserResponse result = authService.register(FIREBASE_UID, mockPhoneToken(), req);

            assertThat(result.roles()).containsExactlyInAnyOrder("SENDER", "TRAVELER");
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
}
