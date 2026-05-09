package com.dony.api.auth;

import com.dony.api.auth.dto.UpgradeToProRequest;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.kyc.KycRepository;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService.upgradeToPro() — tests unitaires")
class UserServiceUpgradeToProTest {

    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private KycRepository kycRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private UserService userService;

    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity buildUser() throws Exception {
        UserEntity u = new UserEntity();
        setId(u, USER_ID);
        u.setFirebaseUid("uid-pro-001");
        u.setPhoneNumber("+33612345678");
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    @Nested
    @DisplayName("Success cases")
    class SuccessCases {

        @Test
        @DisplayName("valid request without stripe account → sets isProAccount=true, audit USER_UPGRADED_TO_PRO")
        void upgradeToPro_validRequest_setsProAccount() throws Exception {
            UserEntity user = buildUser();
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "12345678901234");
            UserEntity result = userService.upgradeToPro(user, request);

            assertThat(result.isProAccount()).isTrue();
            assertThat(result.getProCompanyName()).isEqualTo("Dony SARL");
            assertThat(result.getProSiret()).isEqualTo("12345678901234");
            verify(userRepository).save(user);
            verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_UPGRADED_TO_PRO"), eq(USER_ID), any());
        }

        @Test
        @DisplayName("request with null companyName and siret → still sets isProAccount=true")
        void upgradeToPro_nullFields_setsProAccountTrue() throws Exception {
            UserEntity user = buildUser();
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpgradeToProRequest request = new UpgradeToProRequest(null, null);
            UserEntity result = userService.upgradeToPro(user, request);

            assertThat(result.isProAccount()).isTrue();
            assertThat(result.getProCompanyName()).isNull();
            assertThat(result.getProSiret()).isNull();
        }

        @Test
        @DisplayName("request with blank siret → treated as not-provided, no validation error")
        void upgradeToPro_blankSiret_noValidationError() throws Exception {
            UserEntity user = buildUser();
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpgradeToProRequest request = new UpgradeToProRequest("Mon Entreprise", "   ");
            UserEntity result = userService.upgradeToPro(user, request);

            assertThat(result.isProAccount()).isTrue();
        }

        @Test
        @DisplayName("re-upgrade when already PRO → updates company info, audit USER_PRO_PROFILE_UPDATED")
        void upgradeToPro_alreadyPro_updatesAndEmitsDifferentAuditAction() throws Exception {
            UserEntity user = buildUser();
            user.setProAccount(true);
            user.setProCompanyName("Old Company");
            user.setProSiret("11111111111111");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpgradeToProRequest request = new UpgradeToProRequest("New Company", "22222222222222");
            UserEntity result = userService.upgradeToPro(user, request);

            assertThat(result.isProAccount()).isTrue();
            assertThat(result.getProCompanyName()).isEqualTo("New Company");
            assertThat(result.getProSiret()).isEqualTo("22222222222222");
            // Must use the "update" audit action, not the initial "upgrade" action
            verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_PRO_PROFILE_UPDATED"), eq(USER_ID), any());
            verify(auditService, never()).log(eq("USER"), eq(USER_ID), eq("USER_UPGRADED_TO_PRO"), eq(USER_ID), any());
        }
    }

    @Nested
    @DisplayName("Rejection cases")
    class RejectionCases {

        @Test
        @DisplayName("user with existing Stripe account can still upgrade to PRO")
        void upgradeToPro_stripeAccountExists_succeeds() throws Exception {
            UserEntity user = buildUser();
            user.setStripeAccountId("acct_existing_123");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "12345678901234");
            UserEntity result = userService.upgradeToPro(user, request);

            assertThat(result.isProAccount()).isTrue();
            assertThat(result.getProCompanyName()).isEqualTo("Dony SARL");
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("siret with 13 digits → HTTP 422 Unprocessable Entity")
        void upgradeToPro_siret13Digits_throws422() throws Exception {
            UserEntity user = buildUser();

            UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "1234567890123"); // 13 digits

            assertThatThrownBy(() -> userService.upgradeToPro(user, request))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("invalid-siret");
                    });
        }

        @Test
        @DisplayName("siret with 15 digits → HTTP 422 Unprocessable Entity")
        void upgradeToPro_siret15Digits_throws422() throws Exception {
            UserEntity user = buildUser();

            UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "123456789012345"); // 15 digits

            assertThatThrownBy(() -> userService.upgradeToPro(user, request))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("invalid-siret");
                    });
        }

        @Test
        @DisplayName("siret with letters → HTTP 422 Unprocessable Entity")
        void upgradeToPro_siretWithLetters_throws422() throws Exception {
            UserEntity user = buildUser();

            UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "1234567890ABCD"); // letters

            assertThatThrownBy(() -> userService.upgradeToPro(user, request))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("invalid-siret");
                    });
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static void setId(Object obj, UUID id) throws Exception {
        Field f = obj.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(obj, id);
    }
}
