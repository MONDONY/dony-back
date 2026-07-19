package com.dony.api.payments.wallet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeniusPayCoverageTest {

    @Test void senegalSupportsWaveAndOrange() {
        assertThat(GeniusPayCoverage.supports("SN", "WAVE")).isTrue();
        assertThat(GeniusPayCoverage.supports("SN", "ORANGE_MONEY")).isTrue();
    }

    @Test void senegalDoesNotSupportMtn() {
        assertThat(GeniusPayCoverage.supports("SN", "MTN_MONEY")).isFalse();
    }

    @Test void ivoryCoastSupportsAllThree() {
        assertThat(GeniusPayCoverage.supports("CI", "WAVE")).isTrue();
        assertThat(GeniusPayCoverage.supports("CI", "ORANGE_MONEY")).isTrue();
        assertThat(GeniusPayCoverage.supports("CI", "MTN_MONEY")).isTrue();
    }

    @Test void unknownCountryReturnsFalse() {
        assertThat(GeniusPayCoverage.supports("CM", "WAVE")).isFalse();
    }

    @Test void nullInputsReturnFalse() {
        assertThat(GeniusPayCoverage.supports(null, "WAVE")).isFalse();
        assertThat(GeniusPayCoverage.supports("SN", null)).isFalse();
    }

    @Test void lowercaseCountryCodeStillMatches() {
        assertThat(GeniusPayCoverage.supports("sn", "WAVE")).isTrue();
    }
}
