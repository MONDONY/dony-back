package com.dony.api.emailotp;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmailOtpEntityTest {

    @Test
    void createdAt_setOnPrePersist() {
        EmailOtpEntity e = new EmailOtpEntity();
        e.onCreate();
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void attemptsDefaultsToZero() {
        EmailOtpEntity e = new EmailOtpEntity();
        assertThat(e.getAttempts()).isEqualTo(0);
    }
}
