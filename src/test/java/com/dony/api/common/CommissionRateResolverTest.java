package com.dony.api.common;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.config.DonyConfigProperties;
import com.dony.api.promo.PromoCodeTarget;
import com.dony.api.promo.PromoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionRateResolverTest {

    @Mock UserRepository userRepository;
    @Mock PromoService promoService;

    private CommissionRateResolver resolver() {
        return new CommissionRateResolver(userRepository,
                new DonyConfigProperties(
                        new DonyConfigProperties.Commission(new BigDecimal("0.12")), null, null),
                promoService);
    }

    private UserEntity withOverride(BigDecimal rate) {
        UserEntity u = new UserEntity();
        u.setCommissionRateOverride(rate);
        return u;
    }

    @Test
    void global_rate_when_no_override() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(null)));
        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.12");
    }

    @Test
    void traveler_override_applies_when_lower_than_global() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.08"))));
        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.08");
    }

    @Test
    void takes_most_favorable_min_of_traveler_and_sender() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.10"))));
        when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(new BigDecimal("0.06"))));
        assertThat(resolver().resolve(t, s)).isEqualByComparingTo("0.06");
    }

    @Test
    void override_above_global_never_increases_rate() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.20"))));
        // min(global 0.12, override 0.20) = 0.12 — un override plus élevé ne pénalise pas.
        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.12");
    }

    @Test
    void null_sender_is_ignored() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.09"))));
        assertThat(resolver().resolve(t, null)).isEqualByComparingTo("0.09");
    }

    @Test
    void global_rate_when_user_not_found() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.empty());
        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.12");
    }

    // ── Phase 2 : code promo ─────────────────────────────────────────────────

    @Test
    void promo_overrides_min_rate_when_valid() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        // Override traveler 0.10 < global 0.12, mais le promo à 0.06 doit écraser tout.
        lenient().when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.10"))));
        lenient().when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetRate(eq("PROMO6"), eq(s), any())).thenReturn(new BigDecimal("0.06"));

        assertThat(resolver().resolve(t, s, "PROMO6")).isEqualByComparingTo("0.06");
    }

    @Test
    void invalid_promo_throws_and_does_not_fall_through() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetRate(any(), any(), any()))
                .thenThrow(new com.dony.api.common.DonyBusinessException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "promo-not-found", "Promo Not Found", "Code introuvable"));

        assertThatThrownBy(() -> resolver().resolve(t, s, "BADCODE"))
                .isInstanceOf(com.dony.api.common.DonyBusinessException.class)
                .satisfies(e -> assertThat(((com.dony.api.common.DonyBusinessException) e).getErrorCode())
                        .isEqualTo("promo-not-found"));
    }

    @Test
    void null_promo_falls_back_to_phase1_logic() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.08"))));
        when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));

        // null promoCode → resolve(t, s) → min(0.08, null, 0.12) = 0.08
        assertThat(resolver().resolve(t, s, null)).isEqualByComparingTo("0.08");
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable>
            assertThatThrownBy(org.assertj.core.api.ThrowableAssert.ThrowingCallable code) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(code);
    }
}
