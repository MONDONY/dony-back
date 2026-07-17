package com.dony.api.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DonyConfigPropertiesTest {

    @Test
    void urgencyThresholdDays_defaultsTo3_whenValueAbsent() {
        assertThat(new DonyConfigProperties.Urgency(null).thresholdDays()).isEqualTo(3);
    }

    @Test
    void urgency_defaultsToThreshold3_whenBlockAbsent() {
        DonyConfigProperties config = new DonyConfigProperties(null, null, null);
        assertThat(config.urgency()).isNotNull();
        assertThat(config.urgency().thresholdDays()).isEqualTo(3);
    }

    @Test
    void urgencyThresholdDays_keepsExplicitValue() {
        assertThat(new DonyConfigProperties.Urgency(7).thresholdDays()).isEqualTo(7);
    }
}
