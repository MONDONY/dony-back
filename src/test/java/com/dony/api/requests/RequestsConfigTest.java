package com.dony.api.requests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RequestsConfigTest {

    @Autowired
    private RequestsConfig requestsConfig;

    @Test
    void loadsAllRequiredFieldsFromYaml() {
        // Test profile overrides max rounds to 3 and inactivity to 1
        assertThat(requestsConfig.maxNegotiationRounds()).isEqualTo(3);
        assertThat(requestsConfig.threadInactivityHours()).isEqualTo(1);
        assertThat(requestsConfig.autoExpireCheckCron()).isEqualTo("-");

        // Other fields fall back to application.yml defaults
        assertThat(requestsConfig.dateToleranceDefaultDays()).isEqualTo(2);
        assertThat(requestsConfig.dateToleranceMaxDays()).isEqualTo(7);
        assertThat(requestsConfig.weightKgMin()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(requestsConfig.weightKgMax()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(requestsConfig.declaredValueMaxEur()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(requestsConfig.bodyMaxChars()).isEqualTo(280);
        assertThat(requestsConfig.maxOpenRequestsPerSender()).isEqualTo(10);
        assertThat(requestsConfig.maxOpenThreadsPerTraveler()).isEqualTo(5);
        assertThat(requestsConfig.threadsPerMinuteRateLimit()).isEqualTo(1);
        assertThat(requestsConfig.estimationCorridorRecentTrips()).isEqualTo(20);
        assertThat(requestsConfig.estimationCacheTtlMinutes()).isEqualTo(60);
    }
}
