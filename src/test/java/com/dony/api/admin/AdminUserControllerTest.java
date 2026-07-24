package com.dony.api.admin;

import com.dony.api.auth.UserRepository;
import com.dony.api.auth.UserService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock UserService userService;
    @Mock UserRepository userRepository;
    @Mock com.dony.api.auth.FirebaseContactService firebaseContact;

    // ── Délégation du contrôleur ────────────────────────────────────────────

    @Test
    void setCommissionRate_delegatesToService_andReturnsDetail() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact);
        // Coordonnées servies par Firebase, plus par la base
        when(firebaseContact.getContact(any())).thenReturn(
                com.dony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        BigDecimal rate = new BigDecimal("0.08");
        com.dony.api.auth.UserEntity user = new com.dony.api.auth.UserEntity();
        when(userService.setCommissionRateOverride(userId, rate)).thenReturn(user);

        var resp = controller.setCommissionRate(userId, new CommissionRateOverrideRequest(rate));

        assertThat(resp).isNotNull();
        verify(userService).setCommissionRateOverride(userId, rate);
    }

    @Test
    void setCommissionRate_nullRate_delegatesNull_forGlobalReset() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact);
        // Coordonnées servies par Firebase, plus par la base
        when(firebaseContact.getContact(any())).thenReturn(
                com.dony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        when(userService.setCommissionRateOverride(userId, null))
                .thenReturn(new com.dony.api.auth.UserEntity());

        controller.setCommissionRate(userId, new CommissionRateOverrideRequest(null));

        verify(userService).setCommissionRateOverride(userId, null);
    }

    // ── Recherche : téléphone et email ne sont plus en base ─────────────────

    @Test
    void listUsers_queryOnEmail_resolvesFirebaseUid_andMapsContacts() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact);
        com.dony.api.auth.UserEntity user = new com.dony.api.auth.UserEntity();
        user.setFirebaseUid("uid-awa");

        when(firebaseContact.findUidByEmail("awa@example.com")).thenReturn(java.util.Optional.of("uid-awa"));
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(user)));
        when(firebaseContact.getContacts(java.util.List.of("uid-awa"))).thenReturn(
                java.util.Map.of("uid-awa", new com.dony.api.auth.FirebaseContactService.Contact(
                        "+221701234567", "awa@example.com")));

        var page = controller.listUsers(null, null, null, null, null, "awa@example.com", 0, 20);

        // L'UID résolu est passé à la requête, qui l'apparie exactement
        verify(userRepository).findAdminFiltered(
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("awa@example.com"),
                org.mockito.ArgumentMatchers.eq("%awa@example.com%"),
                org.mockito.ArgumentMatchers.eq("uid-awa"),
                org.mockito.ArgumentMatchers.isNull(), any());
        assertThat(page.getContent()).singleElement()
                .extracting(com.dony.api.admin.dto.AdminUserListItemResponse::phoneNumber)
                .isEqualTo("+221701234567");
    }

    @Test
    void listUsers_queryOnPhone_fallsBackToPhoneLookup() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact);
        when(firebaseContact.findUidByEmail("+221701234567")).thenReturn(java.util.Optional.empty());
        when(firebaseContact.findUidByPhone("+221701234567")).thenReturn(java.util.Optional.of("uid-awa"));
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.listUsers(null, null, null, null, null, "+221701234567", 0, 20);

        verify(firebaseContact).findUidByPhone("+221701234567");
    }

    @Test
    void listUsers_withoutQuery_doesNotHitFirebaseLookups() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact);
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.listUsers(null, null, null, null, null, "   ", 0, 20);

        verify(firebaseContact, org.mockito.Mockito.never()).findUidByEmail(any());
        verify(firebaseContact, org.mockito.Mockito.never()).findUidByPhone(any());
    }

    // ── Bean validation du DTO (@DecimalMin / @DecimalMax) ───────────────────

    private static Validator validator() {
        try (ValidatorFactory f = Validation.buildDefaultValidatorFactory()) {
            return f.getValidator();
        }
    }

    @Test
    void request_validRate_hasNoViolations() {
        assertThat(validator().validate(new CommissionRateOverrideRequest(new BigDecimal("0.08"))))
                .isEmpty();
    }

    @Test
    void request_nullRate_hasNoViolations() {
        // null = retour au taux global, accepté par le DTO.
        assertThat(validator().validate(new CommissionRateOverrideRequest(null))).isEmpty();
    }

    @Test
    void request_negativeRate_isRejected() {
        assertThat(validator().validate(new CommissionRateOverrideRequest(new BigDecimal("-0.01"))))
                .isNotEmpty();
    }

    @Test
    void request_rateOneOrAbove_isRejected() {
        assertThat(validator().validate(new CommissionRateOverrideRequest(new BigDecimal("1.5"))))
                .isNotEmpty();
    }
}
