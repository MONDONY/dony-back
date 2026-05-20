package com.dony.api.auth;

import com.dony.api.auth.dto.UserResponse;
import com.dony.api.auth.events.UserBecameTravelerEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.auth.KycStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserRoleService — tests unitaires")
class UserRoleServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private UserRoleService userRoleService;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        setId(user, UUID.randomUUID());
        user.setFirebaseUid("uid-1");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(new HashSet<>(Set.of(Role.SENDER)));
        user.setKycStatus(KycStatus.VERIFIED);
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        lenient().when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("succès : ajoute TRAVELER, sauvegarde, publie event, audite")
    void activateTraveler_success_addsRole_publishesEvent_audits() {
        when(userRepository.save(user)).thenReturn(user);

        UserResponse result = userRoleService.activateTravelerRole("uid-1");

        assertThat(user.getRoles()).containsExactlyInAnyOrder(Role.TRAVELER, Role.SENDER);
        verify(userRepository).save(user);
        verify(auditService).log(eq("USER"), eq(user.getId()), eq("USER_ROLE_ADDED"),
                eq(user.getId()), eq(Map.of("role", "TRAVELER")));

        ArgumentCaptor<UserBecameTravelerEvent> eventCaptor =
                ArgumentCaptor.forClass(UserBecameTravelerEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo(user.getId());

        assertThat(result.roles()).containsExactlyInAnyOrder("SENDER", "TRAVELER");
    }

    @Test
    @DisplayName("idempotent : déjà TRAVELER → retourne sans modification")
    void activateTraveler_idempotent_returnsUserWithoutModification() {
        user.getRoles().add(Role.TRAVELER);

        userRoleService.activateTravelerRole("uid-1");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    @DisplayName("KYC non vérifié → 409 avec KYC_NOT_VERIFIED dans missingRequirements")
    void activateTraveler_failsWithoutKyc() {
        user.setKycStatus(KycStatus.PENDING);

        assertThatThrownBy(() -> userRoleService.activateTravelerRole("uid-1"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    DonyBusinessException ex = (DonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("traveler-upgrade-requirements-missing");
                    assertThat((List<String>) ex.getProperties().get("missingRequirements"))
                            .containsExactly("KYC_NOT_VERIFIED");
                });
    }

    @Test
    @DisplayName("Stripe non complété → 409 avec STRIPE_ACCOUNT_NOT_COMPLETE")
    void activateTraveler_failsWithoutStripe() {
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        assertThatThrownBy(() -> userRoleService.activateTravelerRole("uid-1"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    DonyBusinessException ex = (DonyBusinessException) e;
                    assertThat((List<String>) ex.getProperties().get("missingRequirements"))
                            .containsExactly("STRIPE_ACCOUNT_NOT_COMPLETE");
                });
    }

    @Test
    @DisplayName("KYC + Stripe manquants → liste les deux dans missingRequirements")
    void activateTraveler_failsWithBoth_listsAllMissing() {
        user.setKycStatus(KycStatus.NOT_STARTED);
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);

        assertThatThrownBy(() -> userRoleService.activateTravelerRole("uid-1"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    DonyBusinessException ex = (DonyBusinessException) e;
                    assertThat((List<String>) ex.getProperties().get("missingRequirements"))
                            .containsExactlyInAnyOrder("KYC_NOT_VERIFIED", "STRIPE_ACCOUNT_NOT_COMPLETE");
                });
    }

    // ─── deactivateTravelerRole ───────────────────────────────────────────────

    @Test
    @DisplayName("désactivation : supprime TRAVELER, sauvegarde, audite")
    void deactivateTraveler_success_removesRole_audits() {
        user.getRoles().add(Role.TRAVELER);
        when(userRepository.save(user)).thenReturn(user);

        UserResponse result = userRoleService.deactivateTravelerRole("uid-1");

        assertThat(user.getRoles()).containsExactly(Role.SENDER);
        verify(userRepository).save(user);
        verify(auditService).log(eq("USER"), eq(user.getId()), eq("USER_ROLE_REMOVED"),
                eq(user.getId()), eq(Map.of("role", "TRAVELER", "reason", "user_self_deactivated")));
        assertThat(result.roles()).containsExactly("SENDER");
    }

    @Test
    @DisplayName("désactivation idempotente : pas TRAVELER → retourne sans modification")
    void deactivateTraveler_idempotent_returnsWithoutChange() {
        userRoleService.deactivateTravelerRole("uid-1");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("désactivation : utilisateur introuvable → 404")
    void deactivateTraveler_userNotFound_throws404() {
        when(userRepository.findByFirebaseUid("uid-unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userRoleService.deactivateTravelerRole("uid-unknown"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("activation : utilisateur introuvable → 404")
    void activateTraveler_userNotFound_throws404() {
        when(userRepository.findByFirebaseUid("uid-unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userRoleService.activateTravelerRole("uid-unknown"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
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
}
