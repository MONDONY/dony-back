package com.yadony.api.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YadonyConfigPropertiesTest {

    @Test
    void urgencyThresholdDays_defaultsTo3_whenValueAbsent() {
        assertThat(new YadonyConfigProperties.Urgency(null).thresholdDays()).isEqualTo(3);
    }

    @Test
    void urgency_defaultsToThreshold3_whenBlockAbsent() {
        YadonyConfigProperties config = new YadonyConfigProperties(null, null, null, null);
        assertThat(config.urgency()).isNotNull();
        assertThat(config.urgency().thresholdDays()).isEqualTo(3);
    }

    @Test
    void urgencyThresholdDays_keepsExplicitValue() {
        assertThat(new YadonyConfigProperties.Urgency(7).thresholdDays()).isEqualTo(7);
    }
}
