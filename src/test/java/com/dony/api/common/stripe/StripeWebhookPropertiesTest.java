package com.dony.api.common.stripe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class StripeWebhookPropertiesTest {

    @Test
    void defaults_applyWhenNullOrZeroValues() {
        var props = new StripeWebhookProperties(null, 0, 0, null, true);
        assertThat(props.pollInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.batchSize()).isEqualTo(50);
        assertThat(props.maxRetries()).isEqualTo(8);
        assertThat(props.retryBackoffBase()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void customValues_arePreserved() {
        var props = new StripeWebhookProperties(Duration.ofSeconds(5), 20, 3, Duration.ofSeconds(10), false);
        assertThat(props.pollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.batchSize()).isEqualTo(20);
        assertThat(props.maxRetries()).isEqualTo(3);
        assertThat(props.retryBackoffBase()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.schedulerEnabled()).isFalse();
    }
}
