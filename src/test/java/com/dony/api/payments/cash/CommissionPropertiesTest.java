package com.dony.api.payments.cash;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CommissionPropertiesTest {

    @Autowired
    CommissionProperties properties;

    @Test
    void loadsRateFromYaml() {
        assertThat(properties.rate()).isEqualByComparingTo(new BigDecimal("0.12"));
    }

    @Test
    void loadsMinimumAmountFromYaml() {
        assertThat(properties.minimumAmount()).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void loadsNoShowContestationHoursFromYaml() {
        assertThat(properties.noShowContestationHours()).isEqualTo(24);
    }
}
