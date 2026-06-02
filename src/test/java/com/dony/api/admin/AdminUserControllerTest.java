package com.dony.api.admin;

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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock UserService userService;

    // ── Délégation du contrôleur ────────────────────────────────────────────

    @Test
    void setCommissionRate_delegatesToService_andReturns204() {
        AdminUserController controller = new AdminUserController(userService);
        UUID userId = UUID.randomUUID();
        BigDecimal rate = new BigDecimal("0.08");

        ResponseEntity<Void> resp =
                controller.setCommissionRate(userId, new CommissionRateOverrideRequest(rate));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(userService).setCommissionRateOverride(userId, rate);
    }

    @Test
    void setCommissionRate_nullRate_delegatesNull_forGlobalReset() {
        AdminUserController controller = new AdminUserController(userService);
        UUID userId = UUID.randomUUID();

        controller.setCommissionRate(userId, new CommissionRateOverrideRequest(null));

        verify(userService).setCommissionRateOverride(userId, null);
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
