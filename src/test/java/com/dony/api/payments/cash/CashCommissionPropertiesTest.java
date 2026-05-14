package com.dony.api.payments.cash;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CashCommissionPropertiesTest {

    @Autowired
    CashCommissionProperties properties;

    @Test
    void loadsOrphanPiCleanupCronFromYaml() {
        assertThat(properties.orphanPiCleanupCron()).isEqualTo("0 */15 * * * *");
    }

    @Test
    void loadsOrphanPiTimeoutMinutesFromYaml() {
        assertThat(properties.orphanPiTimeoutMinutes()).isEqualTo(30);
    }

    @Test
    void loadsNoShowTimeoutCronFromYaml() {
        assertThat(properties.noShowTimeoutCron()).isEqualTo("0 0 * * * *");
    }

    @Test
    void loadsCardExpirationWarningDaysFromYaml() {
        assertThat(properties.cardExpirationWarningDays()).isEqualTo(30);
    }
}
