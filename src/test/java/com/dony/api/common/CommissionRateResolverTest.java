package com.dony.api.common;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.config.DonyConfigProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionRateResolverTest {

    @Mock UserRepository userRepository;

    private CommissionRateResolver resolver() {
        return new CommissionRateResolver(userRepository,
                new DonyConfigProperties(
                        new DonyConfigProperties.Commission(new BigDecimal("0.12")), null, null));
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
}
