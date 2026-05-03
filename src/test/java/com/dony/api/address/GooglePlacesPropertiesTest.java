package com.dony.api.address;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GooglePlacesPropertiesTest {

    @Autowired
    GooglePlacesProperties props;

    @Test
    void apiKeyIsSetInTest() {
        assertThat(props.apiKey()).isEqualTo("test-api-key");
    }

    @Test
    void rateLimitDefaultsTo100InTest() {
        assertThat(props.rateLimitPerMinute()).isEqualTo(100);
    }

    @Test
    void dailyQuotaAutocompleteIsSetInTest() {
        assertThat(props.dailyQuotaAutocomplete()).isEqualTo(999999);
    }

    @Test
    void dailyQuotaDetailsIsSetInTest() {
        assertThat(props.dailyQuotaDetails()).isEqualTo(999999);
    }

    @Test
    void dailyQuotaReverseIsSetInTest() {
        assertThat(props.dailyQuotaReverse()).isEqualTo(999999);
    }

    @Test
    void blockWhenQuotaExceededIsEnabledInTest() {
        assertThat(props.blockWhenQuotaExceeded()).isTrue();
    }
}
