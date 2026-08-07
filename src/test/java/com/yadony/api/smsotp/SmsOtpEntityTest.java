package com.yadony.api.smsotp;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SmsOtpEntityTest {

    @Test
    void createdAt_setOnPrePersist() {
        SmsOtpEntity e = new SmsOtpEntity();
        e.onCreate();
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void attemptsDefaultsToZero() {
        SmsOtpEntity e = new SmsOtpEntity();
        assertThat(e.getAttempts()).isEqualTo(0);
    }
}
